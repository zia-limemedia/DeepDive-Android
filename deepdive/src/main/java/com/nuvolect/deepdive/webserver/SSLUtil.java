/*
 * Copyright (c) 2018 Nuvolect LLC.
 * This software is offered for free under conditions of the GPLv3 open source software license.
 * Contact Nuvolect LLC for a less restrictive commercial license if you would like to use the software
 * without the GPLv3 restrictions.
 */

package com.nuvolect.deepdive.webserver;

import com.nuvolect.deepdive.util.LogUtil;
import com.nuvolect.deepdive.util.OmniFile;
import com.nuvolect.deepdive.util.OmniUtil;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.Provider;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * Configure SSL
 */
public class SSLUtil {

    /**
     * Creates an SSLSocketFactory for HTTPS.
     *
     * Pass a KeyStore resource with your certificate and passphrase
     */
    public static SSLServerSocketFactory configureSSLPath(String path, char[] passphrase) throws IOException {

        SSLServerSocketFactory sslServerSocketFactory = null;
        try {
            // Android does not have the default jks but uses bks
            KeyStore keystore = KeyStore.getInstance("BKS");

            OmniFile loadFile = new OmniFile("u0", path);
            InputStream keystoreStream = loadFile.getFileInputStream();
            keystore.load(keystoreStream, passphrase);

            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(keystore);

            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keystore, passphrase);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
            sslServerSocketFactory = sslContext.getServerSocketFactory();

            String[] defaultCiphersuites = sslServerSocketFactory.getDefaultCipherSuites();
            String[] supportedCipherSuites = sslServerSocketFactory.getSupportedCipherSuites();

            if( LogUtil.DEBUG){

                SSLEngine sslEngine = sslContext.createSSLEngine();
                String[] enabledCipherSuites = sslEngine.getEnabledCipherSuites();
                String[] enabledProtocols = sslEngine.getEnabledProtocols();

                String log = path;
                String algorithm = trustManagerFactory.getAlgorithm();
                Provider provider = trustManagerFactory.getProvider();

                log += "\n\nalgorithm: "+algorithm;
                log += "\n\nprovider: "+provider;
                log += "\n\ndefaultCipherSuites: \n"+Arrays.toString(defaultCiphersuites);
                log += "\n\nsupportedCipherSuites: \n"+Arrays.toString(supportedCipherSuites);
                log += "\n\nenabledCipherSuites: \n"+Arrays.toString(enabledCipherSuites);
                log += "\n\nenabledProtocols: \n"+Arrays.toString(enabledProtocols);

                OmniUtil.writeFile(new OmniFile("u0", "SSL_Factory_"+loadFile.getName()+"_log.txt"), log);

                LogUtil.log("SSL configure successful");
            }

        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }

        return sslServerSocketFactory;
    }
    /**
     * Creates an SSLSocketFactory for HTTPS.
     *
     * Pass a KeyStore resource with your certificate and passphrase
     */
    public static SSLServerSocketFactory configureSSLAsset(String assetCertPath, char[] passphrase) throws IOException {

        SSLServerSocketFactory sslServerSocketFactory = null;
        try {
            // Android does not have the default jks but uses bks
            KeyStore keystore = KeyStore.getInstance("BKS");
            InputStream keystoreStream = WebService.class.getResourceAsStream(assetCertPath);
            keystore.load(keystoreStream, passphrase);
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(keystore);

            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keystore, passphrase);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
            sslServerSocketFactory = sslContext.getServerSocketFactory();

        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }

        return sslServerSocketFactory;
    }

    /**
     * Store certificate to a keystore file.
     * @param cert
     * @param passcode
     * @param outFile
     * @return
     */
    public static boolean storeCertInKeystore( byte [] cert, char [] passcode, OmniFile outFile){

        try {
            FileOutputStream fos = new FileOutputStream( outFile.getStdFile());

            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            InputStream certstream = new ByteArrayInputStream(cert);
            X509Certificate certificate = (X509Certificate) cf.generateCertificate(certstream);

            KeyStore keyStore = KeyStore.getInstance("BKS");
            keyStore.load( null, null);// Initialize it
            keyStore.setCertificateEntry("mycert", certificate);
            keyStore.store( fos, passcode);
            fos.close();

            int numEntries = keyStore.size();
            Long size = outFile.length();

            return true;

        } catch(Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    public static void probeCert(String certPath, char[] passphrase) throws IOException {

        try {
            // Android does not have the default jks but uses bks
            KeyStore keystore = KeyStore.getInstance("BKS");
//            InputStream keystoreStream = WebService.class.getResourceAsStream(certPath);
            InputStream keystoreStream = new OmniFile("u0", certPath).getFileInputStream();
            keystore.load(keystoreStream, passphrase);

            String alias = "";
            Enumeration<String> aliases = keystore.aliases();
            for (; aliases.hasMoreElements(); ) {
                String s = aliases.nextElement();
                LogUtil.log("Alias: " + s);
                if (alias.isEmpty())
                    alias = s;
            }
            Certificate cert = keystore.getCertificate(alias);
            PublicKey pubKey = cert.getPublicKey();
            String alg = pubKey.getAlgorithm();
            LogUtil.log("Public key algorithm: " + alg);
            String certType = cert.getType();
            LogUtil.log("Public key type: " + certType);
            String certString = cert.toString();
            LogUtil.log("Cert string: " + certString);

            Provider provider = keystore.getProvider();
            String providerName = provider.getName();
            LogUtil.log("Provider name: " + providerName);
            String providerInfo = provider.getInfo();
            LogUtil.log("Provider info: " + providerInfo);

            Date creationDate = keystore.getCreationDate(alias);
            LogUtil.log("Creation date: " + creationDate.toString());

            String keystoreType = keystore.getType();
            LogUtil.log("Keystore type: " + keystoreType);

        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }
    }
}
