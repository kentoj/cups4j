package org.cups4j.operations;

/**
 * Copyright (C) 2009 Harald Weyhing
 * <p>
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * <p>
 * See the GNU Lesser General Public License for more details. You should have received a copy of
 * the GNU Lesser General Public License along with this program; if not, see
 * <http://www.gnu.org/licenses/>.
 */

import ch.ethz.vppserver.ippclient.IppResponse;
import ch.ethz.vppserver.ippclient.IppResult;
import ch.ethz.vppserver.ippclient.IppTag;
import ch.ethz.vppserver.schema.ippclient.Attribute;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.cups4j.CupsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.JAXBException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Map;

public abstract class IppOperation {
    private final static String IPP_MIME_TYPE = "application/ipp";
    private static final Logger logger = LoggerFactory.getLogger(IppOperation.class);
    protected short operationID = -1; // IPP operation ID
    protected short bufferSize = 8192; // BufferSize for this operation
    protected int ippPort = CupsClient.DEFAULT_PORT;
    //
    String httpStatusLine = null;


    /**
     * Gets the IPP header
     *
     * @param url The URL to request the IPP header from
     * @return IPP header
     * @throws UnsupportedEncodingException when URL encoding is malformed
     */
    public ByteBuffer getIppHeader(URL url) throws UnsupportedEncodingException {
        return getIppHeader(url, null);
    }


    public IppResult request(URL url, Map<String, String> map) throws Exception {
        return sendRequest(url, getIppHeader(url, map));
    }


    public IppResult request(URL url, Map<String, String> map, InputStream document) throws Exception {
        return sendRequest(url, getIppHeader(url, map), document);
    }


    /**
     * Gets the IPP header
     *
     * @param url The URL to request the IPP header from
     * @param map of HTTP headers
     * @return IPP header
     * @throws UnsupportedEncodingException when URL is malformed
     */
    public ByteBuffer getIppHeader(URL url, Map<String, String> map) throws UnsupportedEncodingException {
        if (url == null) {
            logger.error("IppOperation.getIppHeader(): uri is null");
            return null;
        }

        ByteBuffer ippBuf = ByteBuffer.allocateDirect(bufferSize);
        ippBuf = IppTag.getOperation(ippBuf, operationID);
        ippBuf = IppTag.getUri(ippBuf, "printer-uri", stripPortNumber(url));

        if (map == null) {
            ippBuf = IppTag.getEnd(ippBuf);
            ippBuf.flip();
            return ippBuf;
        }

        ippBuf = IppTag.getNameWithoutLanguage(ippBuf, "requesting-user-name", map.get("requesting-user-name"));

        if (map.get("limit") != null) {
            int value = Integer.parseInt(map.get("limit"));
            ippBuf = IppTag.getInteger(ippBuf, "limit", value);
        }

        if (map.get("requested-attributes") != null) {
            String[] sta = map.get("requested-attributes").split(" ");
            if (sta != null) {
                ippBuf = IppTag.getKeyword(ippBuf, "requested-attributes", sta[0]);
                int l = sta.length;
                for (int i = 1; i < l; i++) {
                    ippBuf = IppTag.getKeyword(ippBuf, null, sta[i]);
                }
            }
        }

        ippBuf = IppTag.getEnd(ippBuf);
        ippBuf.flip();
        return ippBuf;
    }


    /**
     * Sends a request to the provided URL
     *
     * @param url The URL to request the request to
     * @param ippBuf a byte buffer for the request
     *
     * @return result
     * @throws IOException on the ByteBuffer
     * @throws JAXBException for malformed XML
     */
    private IppResult sendRequest(URL url, ByteBuffer ippBuf) throws Exception {
        return sendRequest(url, ippBuf, null);
    }


    /**
     * Sends a request to the provided url
     *
     * @param url The URL to request the request to
     * @param ippBuf a byte buffer for the request
     *
     * @param documentStream the input stream for the document to be printed
     * @return result
     * @throws Exception needs better documentation
     */
    private IppResult sendRequest(URL url, ByteBuffer ippBuf, InputStream documentStream) throws Exception {
        IppResult ippResult = null;
        if (ippBuf == null) {
            return null;
        }

        if (url == null) {
            return null;
        }

        HttpClient client = new DefaultHttpClient();

        // will not work with older versions of CUPS!
        client.getParams().setParameter("http.protocol.version", HttpVersion.HTTP_1_1);
        client.getParams().setParameter("http.socket.timeout", new Integer(10000));
        client.getParams().setParameter("http.connection.timeout", new Integer(10000));
        client.getParams().setParameter("http.protocol.content-charset", "UTF-8");
        client.getParams().setParameter("http.method.response.buffer.warnlimit", new Integer(8092));

        // probabaly not working with older CUPS versions
        client.getParams().setParameter("http.protocol.expect-continue", Boolean.valueOf(true));

        HttpPost httpPost = new HttpPost(new URI("http://" + url.getHost() + ":" + ippPort) + url.getPath());

        httpPost.getParams().setParameter("http.socket.timeout", new Integer(10000));

        byte[] bytes = new byte[ippBuf.limit()];
        ippBuf.get(bytes);

        ByteArrayInputStream headerStream = new ByteArrayInputStream(bytes);

        // If we need to send a document, concatenate InputStreams
        InputStream inputStream = headerStream;
        if (documentStream != null) {
            inputStream = new SequenceInputStream(headerStream, documentStream);
        }

        // set length to -1 to advice the entity to read until EOF
        InputStreamEntity requestEntity = new InputStreamEntity(inputStream, -1);

        requestEntity.setContentType(IPP_MIME_TYPE);
        httpPost.setEntity(requestEntity);

        httpStatusLine = null;

        ResponseHandler<byte[]> handler = new ResponseHandler<byte[]>() {
            public byte[] handleResponse(HttpResponse response) throws ClientProtocolException, IOException {
                HttpEntity entity = response.getEntity();
                httpStatusLine = response.getStatusLine().toString();
                if (entity != null) {
                    return EntityUtils.toByteArray(entity);
                } else {
                    return null;
                }
            }
        };

        byte[] result = client.execute(httpPost, handler);

        IppResponse ippResponse = new IppResponse();

        ippResult = ippResponse.getResponse(ByteBuffer.wrap(result));
        ippResult.setHttpStatusResponse(httpStatusLine);

        // IppResultPrinter.print(ippResult);

        client.getConnectionManager().shutdown();
        return ippResult;
    }


    /**
     * Removes the port number in the submitted URL
     *
     * @param url the URL to strip the port number from
     * @return url without port number
     */
    protected String stripPortNumber(URL url) {
        String protocol = url.getProtocol();
        if ("ipp".equals(protocol)) {
            protocol = "http";
        }

        return protocol + "://" + url.getHost() + url.getPath();
    }


    protected String getAttributeValue(Attribute attr) {
        return attr.getAttributeValue().get(0).getValue();
    }
}
