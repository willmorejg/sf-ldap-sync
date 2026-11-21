package com.acmeinsurance.auth.token;

import static com.acmeinsurance.common.Constants.DIGEST_SHA_256;
import static com.acmeinsurance.common.Constants.KEY_ALGORITHM_RSA;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.jboss.logging.Logger;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwk.Use;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.lang.JoseException;

import com.acmeinsurance.auth.AuthConfig;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The RSA key tokens are signed with, and the JWKS that publishes it.
 *
 * <p>The private key is a PKCS#8 PEM file. With {@code auth.signing.generate=true}
 * (the default) a 3072-bit key is created on first start; keep the file on a
 * volume so tokens stay valid across restarts. To rotate: move the old key aside,
 * list its public key in {@code auth.signing.previous-public-key-files} so tokens
 * signed with it still verify until they expire, and restart.
 */
@Startup
@ApplicationScoped
public class SigningKeys {

    private static final Logger LOG = Logger.getLogger(SigningKeys.class);
    private static final String PEM_PRIVATE_KEY = "PRIVATE KEY";
    private static final String PEM_PUBLIC_KEY = "PUBLIC KEY";
    private static final String PEM_BEGIN = "-----BEGIN ";
    private static final String PEM_END = "-----END ";
    private static final String PEM_DASHES = "-----";
    /** The signing key file is readable by the app's user only. */
    private static final String OWNER_READ_WRITE = "rw-------";

    private final RsaJsonWebKey current;
    private final List<JsonWebKey> published = new ArrayList<>();
    private final String jwks;

    public SigningKeys(final AuthConfig cfg) throws IOException, GeneralSecurityException, JoseException {
        final Path file = Path.of(cfg.signing().keyFile());
        if (!Files.exists(file)) {
            if (!cfg.signing().generate()) {
                throw new IllegalStateException("Signing key " + file + " does not exist and auth.signing.generate=false");
            }
            generate(file);
        }
        final RSAPrivateCrtKey priv = (RSAPrivateCrtKey) KeyFactory.getInstance(KEY_ALGORITHM_RSA)
                .generatePrivate(new PKCS8EncodedKeySpec(pem(Files.readString(file), PEM_PRIVATE_KEY)));
        final PublicKey pub = KeyFactory.getInstance(KEY_ALGORITHM_RSA)
                .generatePublic(new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent()));
        current = publicJwk(pub);
        current.setPrivateKey(priv);

        published.add(publicJwk(pub));
        for (final String prev : cfg.signing().previousPublicKeyFiles().orElse(List.of())) {
            published.add(publicJwk(KeyFactory.getInstance(KEY_ALGORITHM_RSA)
                    .generatePublic(new X509EncodedKeySpec(pem(Files.readString(Path.of(prev)), PEM_PUBLIC_KEY)))));
        }
        jwks = new JsonWebKeySet(published).toJson(JsonWebKey.OutputControlLevel.PUBLIC_ONLY);
        LOG.infof("Signing key %s (kid %s)", file, current.getKeyId());
    }

    /** The key to sign with; its kid is the RFC 7638 thumbprint. */
    public RsaJsonWebKey current() {
        return current;
    }

    /** Every published public key: the current one and any previous ones. */
    public List<JsonWebKey> verificationKeys() {
        return List.copyOf(published);
    }

    /** The JWKS document (public keys only). */
    public String jwks() {
        return jwks;
    }

    private static RsaJsonWebKey publicJwk(final PublicKey pub) throws JoseException {
        final RsaJsonWebKey jwk = (RsaJsonWebKey) JsonWebKey.Factory.newJwk(pub);
        jwk.setKeyId(jwk.calculateBase64urlEncodedThumbprint(DIGEST_SHA_256));
        jwk.setUse(Use.SIGNATURE);
        jwk.setAlgorithm(AlgorithmIdentifiers.RSA_USING_SHA256);
        return jwk;
    }

    private static void generate(final Path file) throws IOException, GeneralSecurityException {
        final KeyPairGenerator g = KeyPairGenerator.getInstance(KEY_ALGORITHM_RSA);
        g.initialize(3072);
        final KeyPair kp = g.generateKeyPair();
        final String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(kp.getPrivate().getEncoded());
        final String text = PEM_BEGIN + PEM_PRIVATE_KEY + PEM_DASHES + "\n" + body + "\n" + PEM_END + PEM_PRIVATE_KEY
                + PEM_DASHES + "\n";
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.createFile(file);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(OWNER_READ_WRITE));
        } catch (final UnsupportedOperationException e) {
            // not a POSIX file system
        }
        Files.writeString(file, text, StandardOpenOption.TRUNCATE_EXISTING);
        LOG.warnf("Generated a new signing key at %s", file);
    }

    private static byte[] pem(final String text, final String type) {
        final String begin = PEM_BEGIN + type + PEM_DASHES;
        final String end = PEM_END + type + PEM_DASHES;
        final int b = text.indexOf(begin);
        final int e = text.indexOf(end);
        if (b < 0 || e < 0) {
            throw new IllegalArgumentException("Not a PEM " + type + " (PKCS#8 / X.509 expected)");
        }
        return Base64.getMimeDecoder().decode(text.substring(b + begin.length(), e));
    }
}
