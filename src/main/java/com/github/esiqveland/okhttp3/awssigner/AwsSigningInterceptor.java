/**
 * Copyright 2017 Eivind Larsen.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.esiqveland.okhttp3.awssigner;

import com.github.esiqveland.okhttp3.awssigner.utils.JCloudTools;
import com.github.esiqveland.okhttp3.awssigner.utils.Tools;
import com.github.esiqveland.okhttp3.awssigner.utils.Utils;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class AwsSigningInterceptor implements Interceptor {
    private static final String AMZ_ALGORITHM_HMAC_SHA256 = "AWS4-HMAC-SHA256";
    private static final String AUTHORIZATION_HEADER = "Authorization";

    private final AwsConfiguration cfg;
    private final Supplier<ZonedDateTime> clock;
    private final DateTimeFormatter timestampFormat;
    private final DateTimeFormatter dateFormat;


    public AwsSigningInterceptor(AwsConfiguration cfg, Supplier<ZonedDateTime> clock) {
        this.cfg = cfg;
        this.clock = clock;
        this.timestampFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneId.of("GMT"));
        this.dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd")
                .withZone(ZoneId.of("GMT"));
    }

    public AwsSigningInterceptor(AwsConfiguration cfg) {
        this(cfg, ZonedDateTime::now);
    }


    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        ZonedDateTime timestamp = clock.get();

        byte[] signatureKey = Tools.getSignatureKey(cfg.awsSecretKey, timestamp, cfg.awsRegion, cfg.awsServiceName);

        String awsAuthorizationHeader = makeAWSAuthorizationHeader(timestamp, request, signatureKey);

        String amzTimestamp = timestampFormat.format(timestamp);

        Request signedRequest = request.newBuilder()
                .removeHeader(AUTHORIZATION_HEADER)
                .addHeader(AUTHORIZATION_HEADER, awsAuthorizationHeader)
                .addHeader("X-Amz-Date", amzTimestamp)
                .addHeader("x-amz-content-sha256", "".sha256())
                .build();

        return chain.proceed(signedRequest);
    }
    
    fun String.sha256(): String {
        val md = MessageDigest.getInstance("SHA-256")
        return BigInteger(1, md.digest(toByteArray())).toString(16).padStart(32, '0')
    }


    @VisibleForTesting
    String makeAWSAuthorizationHeader(ZonedDateTime timestamp, Request request, byte[] signatureKey) throws IOException {
        String datestamp = dateFormat.format(timestamp);

        String credentials = Joiner.on('/').join(
                cfg.awsAccessKey,
                datestamp,
                cfg.awsRegion,
                cfg.awsServiceName,
                "aws4_request"
        );

        CanonicalRequest canonicalRequest = makeCanonicalRequest(
                timestamp,
                request
        );

        Map<String, List<String>> signedHeaders = canonicalRequest.signedHeaders;
        String requestHash = Utils.hexHash(canonicalRequest.canonicalRequest);
        String stringToSign = createStringToSign(timestamp, requestHash);
        String signature = Tools.createSignature(signatureKey, stringToSign);

        String signedHeadersStr = Joiner.on(";").join(signedHeaders.keySet());

        StringBuilder authorization = new StringBuilder(AMZ_ALGORITHM_HMAC_SHA256).append(" ")
                .append("Credential=").append(credentials)
                .append(", ")
                .append("SignedHeaders=").append(signedHeadersStr)
                .append(", ")
                .append("Signature=").append(signature);

        return authorization.toString();
    }

    static class CanonicalRequest {
        final String canonicalRequest;
        // signedHeaders includes a copy of the headers we chose to include for the signature
        final Map<String, List<String>> signedHeaders;

        CanonicalRequest(String canonicalRequest, Map<String, List<String>> signedHeaders) {
            this.canonicalRequest = canonicalRequest;
            this.signedHeaders = signedHeaders;
        }
    }


    // makeCanonicalRequest creates a string representing a request for the purpose of signing it as a AWS
    // signed request.
    // See also: http://docs.aws.amazon.com/general/latest/gr/sigv4-create-canonical-request.html
    @VisibleForTesting
    CanonicalRequest makeCanonicalRequest(ZonedDateTime timestamp, Request request) throws IOException {
        RequestBody body = request.body();
        String bodyHash = JCloudTools.getEmptyPayloadContentHash();
        if (body != null) {
            Buffer sink = new Buffer();
            body.writeTo(sink);
            bodyHash = sink.sha256().hex();
        }

        HttpUrl url = request.url();
        String canonicalPath = Tools.getCanonicalPath(url);

        Map<String, List<String>> headersToSign = request.headers().toMultimap();

        // replace x-amz-date with one we know is same as the timestamp we have signed in the signature
        String amzTimestamp = timestampFormat.format(timestamp);
        headersToSign.remove("x-amz-date");
        headersToSign.put("x-amz-date", Lists.newArrayList(amzTimestamp));

        String canonicalHeaders = Tools.createCanonicalHeaderString(headersToSign);

        String canonicalQueryString = Tools.createCanonicalQueryString(request.url());

        String signedHead = Joiner.on(";").join(headersToSign.keySet());

        // CanonicalRequest =
        //        HTTPRequestMethod + '\n' +
        //                CanonicalURI + '\n' +
        //                CanonicalQueryString + '\n' +
        //                CanonicalHeaders + '\n' +
        //                SignedHeaders + '\n' +
        //                HexEncode(Hash(RequestPayload))

        String canonicalRequest = request.method() + '\n' +
                canonicalPath + '\n' +
                canonicalQueryString + '\n' +
                canonicalHeaders + '\n' + '\n' +
                signedHead + '\n' +
                bodyHash;

        return new CanonicalRequest(canonicalRequest, headersToSign);
    }

    @VisibleForTesting
    String createStringToSign(ZonedDateTime timestamp, String requestHash) {
        String dateTime = timestampFormat.format(timestamp);
        String datestamp = dateFormat.format(timestamp);

        String credentialScope = Joiner.on('/').join(datestamp, cfg.awsRegion, cfg.awsServiceName, "aws4_request");

        return "AWS4-HMAC-SHA256" + '\n' +
                dateTime + '\n' +
                credentialScope + '\n' +
                requestHash;

    }
}
