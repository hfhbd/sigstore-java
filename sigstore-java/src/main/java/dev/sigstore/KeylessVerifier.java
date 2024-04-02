/*
 * Copyright 2022 The Sigstore Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.sigstore;

import com.google.api.client.util.Preconditions;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import dev.sigstore.encryption.certificates.Certificates;
import dev.sigstore.encryption.signers.Verifiers;
import dev.sigstore.fulcio.client.FulcioCertificateVerifier;
import dev.sigstore.fulcio.client.FulcioVerificationException;
import dev.sigstore.fulcio.client.FulcioVerifier;
import dev.sigstore.rekor.client.HashedRekordRequest;
import dev.sigstore.rekor.client.RekorClient;
import dev.sigstore.rekor.client.RekorEntry;
import dev.sigstore.rekor.client.RekorParseException;
import dev.sigstore.rekor.client.RekorVerificationException;
import dev.sigstore.rekor.client.RekorVerifier;
import dev.sigstore.trustroot.TransparencyLog;
import dev.sigstore.tuf.SigstoreTufClient;
import java.io.IOException;
import java.nio.file.Path;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.sql.Date;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.bouncycastle.util.encoders.Hex;

/** Verify hashrekords from rekor signed using the keyless signing flow with fulcio certificates. */
public class KeylessVerifier {
  private final FulcioVerifier fulcioVerifier;
  private final RekorVerifier rekorVerifier;

  // a client per remote trusted log
  private final List<RekorClient> rekorClients;

  private KeylessVerifier(
      FulcioVerifier fulcioVerifier, List<RekorClient> rekorClients, RekorVerifier rekorVerifier) {
    this.fulcioVerifier = fulcioVerifier;
    this.rekorVerifier = rekorVerifier;
    this.rekorClients = rekorClients;
  }

  public static KeylessVerifier.Builder builder() {
    return new KeylessVerifier.Builder();
  }

  public static class Builder {
    private TrustedRootProvider trustedRootProvider;

    public KeylessVerifier build()
        throws InvalidAlgorithmParameterException, CertificateException, InvalidKeySpecException,
            NoSuchAlgorithmException, IOException, InvalidKeyException {
      Preconditions.checkNotNull(trustedRootProvider);
      var trustedRoot = trustedRootProvider.get();
      var fulcioVerifier = FulcioVerifier.newFulcioVerifier(trustedRoot);
      var rekorVerifier = RekorVerifier.newRekorVerifier(trustedRoot);
      var rekorClients =
          trustedRoot.getTLogs().stream()
              .map(TransparencyLog::getBaseUrl)
              .distinct()
              .map(uri -> RekorClient.builder().setUri(uri).build())
              .collect(Collectors.toList());
      return new KeylessVerifier(fulcioVerifier, rekorClients, rekorVerifier);
    }

    public Builder sigstorePublicDefaults() {
      var sigstoreTufClientBuilder = SigstoreTufClient.builder().usePublicGoodInstance();
      trustedRootProvider = TrustedRootProvider.from(sigstoreTufClientBuilder);
      return this;
    }

    public Builder sigstoreStagingDefaults() {
      var sigstoreTufClientBuilder = SigstoreTufClient.builder().useStagingInstance();
      trustedRootProvider = TrustedRootProvider.from(sigstoreTufClientBuilder);
      return this;
    }

    public Builder fromTrustedRoot(Path trustedRoot) {
      trustedRootProvider = TrustedRootProvider.from(trustedRoot);
      return this;
    }
  }

  /** Convenience wrapper around {@link #verify(byte[], KeylessVerificationRequest)}. */
  public void verify(Path artifact, KeylessVerificationRequest request)
      throws KeylessVerificationException {
    try {
      byte[] artifactDigest =
          Files.asByteSource(artifact.toFile()).hash(Hashing.sha256()).asBytes();
      verify(artifactDigest, request);
    } catch (IOException e) {
      throw new KeylessVerificationException("Could not hash provided artifact path: " + artifact);
    }
  }

  /**
   * Verify that the inputs can attest to the validity of a signature using sigstore's keyless
   * infrastructure. If no exception is thrown, it should be assumed verification has passed.
   *
   * @param artifactDigest the sha256 digest of the artifact that is being verified
   * @param request the keyless verification data and options
   * @throws KeylessVerificationException if the signing information could not be verified
   */
  public void verify(byte[] artifactDigest, KeylessVerificationRequest request)
      throws KeylessVerificationException {
    var signingCert = request.getKeylessSignature().getCertPath();
    var leafCert = Certificates.getLeaf(signingCert);

    // this ensures the provided artifact digest matches what may have come from a bundle (in
    // keyless signature)
    var digest = request.getKeylessSignature().getDigest();
    if (digest.length > 0) {
      if (!Arrays.equals(artifactDigest, digest)) {
        throw new KeylessVerificationException(
            "Provided artifact sha256 digest does not match digest used for verification"
                + "\nprovided(hex) : "
                + Hex.toHexString(artifactDigest)
                + "\nverification  : "
                + Hex.toHexString(digest));
      }
    }

    // verify the certificate chains up to a trusted root (fulcio) and contains a valid SCT from
    // a trusted CT log
    try {
      fulcioVerifier.verifySigningCertificate(signingCert);
    } catch (FulcioVerificationException | IOException ex) {
      throw new KeylessVerificationException(
          "Fulcio certificate was not valid: " + ex.getMessage(), ex);
    }

    // verify the certificate identity if options are present
    if (request.getVerificationOptions().getCertificateIdentities().size() > 0) {
      try {
        new FulcioCertificateVerifier()
            .verifyCertificateMatches(
                leafCert, request.getVerificationOptions().getCertificateIdentities());
      } catch (FulcioVerificationException fve) {
        throw new KeylessVerificationException(
            "Could not verify certificate identities: " + fve.getMessage(), fve);
      }
    }

    var signature = request.getKeylessSignature().getSignature();

    RekorEntry rekorEntry;
    if (request.getVerificationOptions().alwaysUseRemoteRekorEntry()
        || request.getKeylessSignature().getEntry().isEmpty()) {
      // this setting means we ignore any provided entry
      rekorEntry = getEntryFromRekor(artifactDigest, leafCert, signature);
    } else {
      rekorEntry =
          request
              .getKeylessSignature()
              .getEntry()
              .orElseThrow(
                  () ->
                      new KeylessVerificationException(
                          "No rekor entry was provided for offline verification"));
    }

    // verify the rekor entry is signed by the log keys
    try {
      rekorVerifier.verifyEntry(rekorEntry);
    } catch (RekorVerificationException ex) {
      throw new KeylessVerificationException("Rekor entry signature was not valid", ex);
    }

    // check if the time of entry inclusion in the log (a stand-in for signing time) is within the
    // validity period for the certificate
    var entryTime = Date.from(rekorEntry.getIntegratedTimeInstant());
    try {
      leafCert.checkValidity(entryTime);
    } catch (CertificateNotYetValidException e) {
      throw new KeylessVerificationException("Signing time was before certificate validity", e);
    } catch (CertificateExpiredException e) {
      throw new KeylessVerificationException("Signing time was after certificate expiry", e);
    }

    // finally check the supplied signature can be verified by the public key in the certificate
    var publicKey = leafCert.getPublicKey();
    try {
      var verifier = Verifiers.newVerifier(publicKey);
      if (!verifier.verifyDigest(artifactDigest, signature)) {
        throw new KeylessVerificationException("Artifact signature was not valid");
      }
    } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
      throw new RuntimeException(ex);
    } catch (SignatureException ex) {
      throw new KeylessVerificationException(
          "Signature could not be processed: " + ex.getMessage(), ex);
    }
  }

  private RekorEntry getEntryFromRekor(
      byte[] artifactDigest, X509Certificate leafCert, byte[] signature)
      throws KeylessVerificationException {
    // rebuild the hashedRekord so we can query the log for it
    HashedRekordRequest hashedRekordRequest;
    try {
      hashedRekordRequest =
          HashedRekordRequest.newHashedRekordRequest(
              artifactDigest, Certificates.toPemBytes(leafCert), signature);
    } catch (IOException e) {
      throw new KeylessVerificationException(
          "Could not convert certificate to PEM when recreating hashrekord", e);
    }
    Optional<RekorEntry> rekorEntry;

    // attempt to grab a valid rekord from all known rekor instances
    try {
      for (var rekorClient : rekorClients) {
        rekorEntry = rekorClient.getEntry(hashedRekordRequest);
        if (rekorEntry.isPresent()) {
          var entryTime = Date.from(rekorEntry.get().getIntegratedTimeInstant());
          try {
            // only return this entry if it's valid for the certificate
            leafCert.checkValidity(entryTime);
          } catch (CertificateExpiredException | CertificateNotYetValidException ex) {
            continue;
          }
          return rekorEntry.get();
        }
      }
    } catch (IOException | RekorParseException e) {
      throw new KeylessVerificationException("Could not retrieve rekor entry", e);
    }
    throw new KeylessVerificationException("No valid rekor entry was not found in any known logs");
  }
}
