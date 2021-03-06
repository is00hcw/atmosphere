/*
 * Copyright 2013 Jeanfrancois Arcand
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.client;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsyncIOInterceptorAdapter;
import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereInterceptorWriter;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.Arrays;
import java.util.HashSet;

import static org.atmosphere.cpr.ApplicationConfig.MESSAGE_DELIMITER;
import static org.atmosphere.cpr.ApplicationConfig.EXCLUDED_CONTENT_TYPES;

/**
 * An {@link org.atmosphere.cpr.AtmosphereInterceptor} that add a message size and delimiter.
 * <p/>
 * The special String is configurable using {@link org.atmosphere.cpr.ApplicationConfig#MESSAGE_DELIMITER} and
 * you can configure this class to exclude some response's content-type by using the {@link ApplicationConfig#EXCLUDED_CONTENT_TYPES}
 *
 * @author Jeanfrancois Arcand
 */
public class TrackMessageSizeInterceptor extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(TrackMessageSizeInterceptor.class);
    private final static byte[] END = "|".getBytes();
    private final static String IN_ENCODING = "UTF-8";
    private final static String OUT_ENCODING = "UTF-8";
    public final static String SKIP_INTERCEPTOR = TrackMessageSizeInterceptor.class.getName() + ".skip";

    private byte[] end = END;
    private String endString = "|";
    private final Charset inCharset = Charset.forName(IN_ENCODING);
    private final Charset outCharset = Charset.forName(OUT_ENCODING);
    private final HashSet<String> excludedContentTypes = new HashSet<String>();

    private final Interceptor interceptor = new Interceptor();

    @Override
    public void configure(AtmosphereConfig config) {
        String s = config.getInitParameter(MESSAGE_DELIMITER);
        if (s != null) {
            messageDelimiter(s);
        }
        s = config.getInitParameter(EXCLUDED_CONTENT_TYPES);
        if (s != null) {
            excludedContentTypes.addAll(Arrays.asList(s.split(",")));
        }
    }

    /**
     * Set the character delimiter used by this class to separate message.
     * @param endString
     * @return this
     */
    public TrackMessageSizeInterceptor messageDelimiter(String endString) {
        this.endString = endString;
        end = endString.getBytes();
        return this;
    }

    /**
     * Exclude response's content-type from being processed by this class.
     * @param excludedContentType the value of {@link org.atmosphere.cpr.AtmosphereResponse#getContentType()}
     * @return this
     */
    public TrackMessageSizeInterceptor excludedContentType(String excludedContentType) {
        excludedContentTypes.add(excludedContentType.toLowerCase());
        return this;
    }

    @Override
    public Action inspect(final AtmosphereResource r) {
        if (AtmosphereResource.TRANSPORT.UNDEFINED == r.transport())
            return Action.CONTINUE;

        final AtmosphereResponse response = r.getResponse();

        super.inspect(r);

        AsyncIOWriter writer = response.getAsyncIOWriter();
        if (AtmosphereInterceptorWriter.class.isAssignableFrom(writer.getClass())) {
            AtmosphereInterceptorWriter.class.cast(writer).interceptor(interceptor);
        } else {
            logger.warn("Unable to apply {}. Your AsyncIOWriter must implement {}", getClass().getName(), AtmosphereInterceptorWriter.class.getName());
        }
        return Action.CONTINUE;
    }

    @Override
    public String toString() {
        return " Track Message Size Interceptor using " + endString;
    }

    private final class Interceptor extends AsyncIOInterceptorAdapter {
        @Override
        public byte[] transformPayload(AtmosphereResponse response, byte[] responseDraft, byte[] data) throws IOException {

            if (response.request().getAttribute(SKIP_INTERCEPTOR) == null
                    && (response.getContentType() == null
                    || !excludedContentTypes.contains(response.getContentType().toLowerCase()))) {
                response.setCharacterEncoding(OUT_ENCODING);

                CharBuffer cb = inCharset.newDecoder().decode(ByteBuffer.wrap(responseDraft, 0, responseDraft.length));
                String s = cb.toString();

                if (s.trim().length() == 0 || s.indexOf(endString) != -1) {
                    return responseDraft;
                }

                int size = cb.length();
                CharBuffer cb2 = CharBuffer.wrap(Integer.toString(size) + endString);
                ByteBuffer bb = ByteBuffer.allocate((cb2.length() + size) * 2);
                CharsetEncoder encoder = outCharset.newEncoder();
                encoder.encode(cb2, bb, false);
                encoder.encode(cb, bb, false);
                bb.flip();
                byte[] b = new byte[bb.limit()];
                bb.get(b);
                return b;
            } else {
                return responseDraft;
            }
        }
    }
}
