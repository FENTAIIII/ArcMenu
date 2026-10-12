package com.fentai.arcmenu.protocol;

import java.net.Socket;
import java.net.http.HttpClient;
import java.security.*;
import java.security.cert.*;
import java.time.Duration;
import java.util.HexFormat;
import javax.net.ssl.*;

/** Private marketplace certificate pins retain HTTPS endpoint-name verification. */
public final class MarketTls {
    private MarketTls() {}
    public static HttpClient httpClient(String certificateSha256) {
        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER);
        if (certificateSha256 != null && !certificateSha256.isBlank()) {
            byte[] expected;
            try { expected = HexFormat.of().parseHex(certificateSha256.replace(":", "").trim()); }
            catch (IllegalArgumentException error) { throw new IllegalArgumentException("Invalid marketplace certificate pin", error); }
            if (expected.length != 32) throw new IllegalArgumentException("Marketplace certificate pin must contain 32 SHA-256 bytes");
            try {
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[] { new PinnedTrust(expected) }, new SecureRandom());
                builder.sslContext(context);
            } catch (GeneralSecurityException error) { throw new IllegalStateException("Marketplace TLS could not be initialized", error); }
        }
        return builder.build();
    }
    private static final class PinnedTrust extends X509ExtendedTrustManager {
        private final byte[] expected;
        PinnedTrust(byte[] expected) { this.expected = expected.clone(); }
        private void verify(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("Marketplace certificate is missing");
            chain[0].checkValidity();
            try {
                byte[] actual = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                if (!MessageDigest.isEqual(expected, actual)) throw new CertificateException("Marketplace certificate does not match its configured pin");
            } catch (NoSuchAlgorithmException error) { throw new CertificateException(error); }
        }
        private void verifyEndpoint(X509Certificate[] chain, String authType, Socket socket, SSLEngine engine) throws CertificateException {
            verify(chain);
            // The standard trust-manager wrapper enforces endpoint identification even with a private pin.
            try {
                KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType()); store.load(null, null);
                store.setCertificateEntry("marketplace", chain[0]);
                TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); factory.init(store);
                for (TrustManager manager : factory.getTrustManagers()) if (manager instanceof X509ExtendedTrustManager verifier) {
                    if (engine != null) verifier.checkServerTrusted(chain, authType, engine);
                    else if (socket != null) verifier.checkServerTrusted(chain, authType, socket);
                    else verifier.checkServerTrusted(chain, authType);
                    return;
                }
                throw new CertificateException("HTTPS endpoint verifier is unavailable");
            } catch (CertificateException error) { throw error; }
            catch (Exception error) { throw new CertificateException("Marketplace endpoint verification failed", error); }
        }
        @Override public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException { verifyEndpoint(chain, type, null, null); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String type, Socket socket) throws CertificateException { verifyEndpoint(chain, type, socket, null); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String type, SSLEngine engine) throws CertificateException { verifyEndpoint(chain, type, null, engine); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String type) throws CertificateException { throw new CertificateException("Client certificates are not supported"); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String type, Socket socket) throws CertificateException { checkClientTrusted(chain, type); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String type, SSLEngine engine) throws CertificateException { checkClientTrusted(chain, type); }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
