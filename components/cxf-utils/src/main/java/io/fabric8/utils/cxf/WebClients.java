/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.utils.cxf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.jaxrs.cfg.Annotations;
import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider;
import io.fabric8.utils.Strings;
import net.oauth.signature.pem.PEMReader;
import net.oauth.signature.pem.PKCS1EncodedKeySpec;
import org.apache.cxf.configuration.jsse.TLSClientParameters;
import org.apache.cxf.jaxrs.client.WebClient;
import org.apache.cxf.transport.http.HTTPConduit;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.fabric8.utils.cxf.JsonHelper.createObjectMapper;

/**
 */
public class WebClients {
    private static final transient Logger LOG = LoggerFactory.getLogger(WebClients.class);

    public static InputStream getInputStreamFromDataOrFile(String data, File file) throws FileNotFoundException {
        if (data != null) {
            return new ByteArrayInputStream(data.getBytes());
        }
        if (file != null) {
            return new FileInputStream(file);
        }
        return null;
    }

    public static void configureCaCert(WebClient webClient, String caCertData, File caCertFile) {
        try (InputStream pemInputStream = getInputStreamFromDataOrFile(caCertData, caCertFile)) {
            CertificateFactory certFactory = CertificateFactory.getInstance("X509");
            X509Certificate cert = (X509Certificate) certFactory.generateCertificate(pemInputStream);

            KeyStore trustStore = KeyStore.getInstance("JKS");
            trustStore.load(null);

            String alias = cert.getSubjectX500Principal().getName();
            trustStore.setCertificateEntry(alias, cert);

            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);

            HTTPConduit conduit = WebClient.getConfig(webClient)
                    .getHttpConduit();

            TLSClientParameters params = conduit.getTlsClientParameters();

            if (params == null) {
                params = new TLSClientParameters();
                conduit.setTlsClientParameters(params);
            }

            TrustManager[] existingTrustManagers = params.getTrustManagers();
            TrustManager[] trustManagers;

            if (existingTrustManagers == null || ArrayUtils.isEmpty(existingTrustManagers)) {
                trustManagers = trustManagerFactory.getTrustManagers();
            } else {
                trustManagers = (TrustManager[]) ArrayUtils.addAll(existingTrustManagers, trustManagerFactory.getTrustManagers());
            }

            params.setTrustManagers(trustManagers);

        } catch (Exception e) {
            LOG.error("Could not create trust manager for " + caCertFile, e);
        }
    }

    public static void disableSslChecks(WebClient webClient) {
        HTTPConduit conduit = WebClient.getConfig(webClient)
                .getHttpConduit();

        TLSClientParameters params = conduit.getTlsClientParameters();

        if (params == null) {
            params = new TLSClientParameters();
            conduit.setTlsClientParameters(params);
        }

        params.setTrustManagers(new TrustManager[]{new TrustEverythingSSLTrustManager()});

        params.setDisableCNCheck(true);
    }

    public static void configureClientCert(WebClient webClient, String clientCertData, File clientCertFile, String clientKeyData, File clientKeyFile, String clientKeyAlgo, char[] clientKeyPassword) {
        try (InputStream certInputStream = getInputStreamFromDataOrFile(clientCertData, clientCertFile)) {
            CertificateFactory certFactory = CertificateFactory.getInstance("X509");
            X509Certificate cert = (X509Certificate) certFactory.generateCertificate(certInputStream);

            InputStream keyInputStream = getInputStreamFromDataOrFile(clientKeyData, clientKeyFile);
            PEMReader reader = new PEMReader(keyInputStream);
            RSAPrivateCrtKeySpec keySpec = new PKCS1EncodedKeySpec(reader.getDerBytes()).getKeySpec();
            KeyFactory kf = KeyFactory.getInstance(clientKeyAlgo);
            RSAPrivateKey privKey = (RSAPrivateKey) kf.generatePrivate(keySpec);

            KeyStore keyStore = KeyStore.getInstance("JKS");
            keyStore.load(null);

            String alias = cert.getSubjectX500Principal().getName();
            keyStore.setKeyEntry(alias, privKey, clientKeyPassword, new Certificate[] {cert});

            KeyManagerFactory keyManagerFactory =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, clientKeyPassword);

            HTTPConduit conduit = WebClient.getConfig(webClient)
                    .getHttpConduit();

            TLSClientParameters params = conduit.getTlsClientParameters();

            if (params == null) {
                params = new TLSClientParameters();
                conduit.setTlsClientParameters(params);
            }

            KeyManager[] existingKeyManagers = params.getKeyManagers();
            KeyManager[] keyManagers;

            if (existingKeyManagers == null || ArrayUtils.isEmpty(existingKeyManagers)) {
                keyManagers = keyManagerFactory.getKeyManagers();
            } else {
                keyManagers = (KeyManager[]) ArrayUtils.addAll(existingKeyManagers, keyManagerFactory.getKeyManagers());
            }

            params.setKeyManagers(keyManagers);

        } catch (Exception e) {
            LOG.error("Could not create key manager for " + clientCertFile + " (" + clientKeyFile + ")", e);
        }
    }

    public static void configureUserAndPassword(WebClient webClient, String username, String password) {
        if (Strings.isNotBlank(username) && Strings.isNotBlank(password)) {
            HTTPConduit conduit = WebClient.getConfig(webClient).getHttpConduit();
            conduit.getAuthorization().setUserName(username);
            conduit.getAuthorization().setPassword(password);
        }
    }

    public static List<Object> createProviders() {
        List<Object> providers = new ArrayList<Object>();
        Annotations[] annotationsToUse = JacksonJaxbJsonProvider.DEFAULT_ANNOTATIONS;
        ObjectMapper objectMapper = createObjectMapper();
        providers.add(new JacksonJaxbJsonProvider(objectMapper, annotationsToUse));
        providers.add(new ExceptionResponseMapper());
        return providers;
    }
}
