package org.talend.esb.policy.transformation.interceptor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.util.logging.Logger;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.Templates;
import javax.xml.transform.stream.StreamSource;

import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.feature.transform.XSLTOutInterceptor;
import org.apache.cxf.feature.transform.XSLTUtils;
import org.apache.cxf.feature.transform.XSLTOutInterceptor.XSLTCachedOutputStreamCallback;
import org.apache.cxf.feature.transform.XSLTOutInterceptor.XSLTCachedWriter;
import org.apache.cxf.feature.transform.XSLTOutInterceptor.XSLTStreamWriter;
import org.apache.cxf.helpers.IOUtils;
import org.apache.cxf.interceptor.AbstractOutDatabindingInterceptor;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.interceptor.StaxOutInterceptor;
import org.apache.cxf.io.CachedOutputStream;
import org.apache.cxf.io.CachedOutputStreamCallback;
import org.apache.cxf.io.CachedWriter;
import org.apache.cxf.message.Message;
import org.apache.cxf.phase.Phase;
import org.apache.cxf.staxutils.DelegatingXMLStreamWriter;
import org.apache.cxf.staxutils.StaxUtils;

public class XslPathProtocolAwareXSLTOutInterceptor extends
        XslPathProtocolAwareXSLTInterceptor {

      private static final Logger LOG = LogUtils.getL7dLogger(XSLTOutInterceptor.class);

        public XslPathProtocolAwareXSLTOutInterceptor(String xsltPath) {
            super(Phase.PRE_STREAM, StaxOutInterceptor.class, null, xsltPath);
        }

        public XslPathProtocolAwareXSLTOutInterceptor(String phase, Class<?> before, Class<?> after, String xsltPath) {
            super(phase, before, after, xsltPath);
        }

        @Override
        public void handleMessage(Message message) {
            if (checkContextProperty(message)) {
                return;
            }

            // 1. Try to get and transform XMLStreamWriter message content
            XMLStreamWriter xWriter = message.getContent(XMLStreamWriter.class);
            if (xWriter != null) {
                transformXWriter(message, xWriter);
            } else {
                // 2. Try to get and transform OutputStream message content
                OutputStream out = message.getContent(OutputStream.class);
                if (out != null) {
                    transformOS(message, out);
                } else {
                    // 3. Try to get and transform Writer message content (actually used for JMS TextMessage)
                    Writer writer = message.getContent(Writer.class);
                    if (writer != null) {
                        transformWriter(message, writer);
                    }
                }
            }
        }

        protected void transformXWriter(Message message, XMLStreamWriter xWriter) {
            CachedWriter writer = new CachedWriter();
            XMLStreamWriter delegate = StaxUtils.createXMLStreamWriter(writer);
            XSLTStreamWriter wrapper = new XSLTStreamWriter(getXSLTTemplate(), writer, delegate, xWriter);
            message.setContent(XMLStreamWriter.class, wrapper);
            message.put(AbstractOutDatabindingInterceptor.DISABLE_OUTPUTSTREAM_OPTIMIZATION,
                        Boolean.TRUE);
        }

        protected void transformOS(Message message, OutputStream out) {
            CachedOutputStream wrapper = new CachedOutputStream();
            CachedOutputStreamCallback callback = new XSLTCachedOutputStreamCallback(getXSLTTemplate(), out);
            wrapper.registerCallback(callback);
            message.setContent(OutputStream.class, wrapper);
        }

        protected void transformWriter(Message message, Writer writer) {
            XSLTCachedWriter wrapper = new XSLTCachedWriter(getXSLTTemplate(), writer);
            message.setContent(Writer.class, wrapper);
        }


        public static class XSLTStreamWriter extends DelegatingXMLStreamWriter {
            private final Templates xsltTemplate;
            private final CachedWriter cachedWriter;
            private final XMLStreamWriter origXWriter;

            public XSLTStreamWriter(Templates xsltTemplate, CachedWriter cachedWriter,
                                    XMLStreamWriter delegateXWriter, XMLStreamWriter origXWriter) {
                super(delegateXWriter);
                this.xsltTemplate = xsltTemplate;
                this.cachedWriter = cachedWriter;
                this.origXWriter = origXWriter;
            }

            @Override
            public void close() {
                Reader transformedReader = null;
                try {
                    super.flush();
                    transformedReader = XSLTUtils.transform(xsltTemplate, cachedWriter.getReader());
                    StaxUtils.copy(new StreamSource(transformedReader), origXWriter);
                } catch (XMLStreamException e) {
                    throw new Fault("STAX_COPY", LOG, e, e.getMessage());
                } catch (IOException e) {
                    throw new Fault("GET_CACHED_INPUT_STREAM", LOG, e, e.getMessage());
                } finally {
                    try {
                        if (transformedReader != null) {
                            transformedReader.close();
                        }
                        cachedWriter.close();
                        StaxUtils.close(origXWriter);
                        super.close();
                    } catch (Exception e) {
                        LOG.warning("Cannot close stream after transformation: " + e.getMessage());
                    }
                }
            }
        }

        public static class XSLTCachedOutputStreamCallback implements CachedOutputStreamCallback {
            private final Templates xsltTemplate;
            private final OutputStream origStream;

            public XSLTCachedOutputStreamCallback(Templates xsltTemplate, OutputStream origStream) {
                this.xsltTemplate = xsltTemplate;
                this.origStream = origStream;
            }

            @Override
            public void onFlush(CachedOutputStream wrapper) {
            }

            @Override
            public void onClose(CachedOutputStream wrapper) {
                InputStream transformedStream = null;
                try {
                    transformedStream = XSLTUtils.transform(xsltTemplate, wrapper.getInputStream());
                    IOUtils.copyAndCloseInput(transformedStream, origStream);
                } catch (IOException e) {
                    throw new Fault("STREAM_COPY", LOG, e, e.getMessage());
                } finally {
                    try {
                        origStream.close();
                    } catch (IOException e) {
                        LOG.warning("Cannot close stream after transformation: " + e.getMessage());
                    }
                }
            }
        }

        public static class XSLTCachedWriter extends CachedWriter {
            private final Templates xsltTemplate;
            private final Writer origWriter;

            public XSLTCachedWriter(Templates xsltTemplate, Writer origWriter) {
                this.xsltTemplate = xsltTemplate;
                this.origWriter = origWriter;
            }

            @Override
            protected void doClose() {
                Reader transformedReader = null;
                try {
                    transformedReader = XSLTUtils.transform(xsltTemplate, getReader());
                    IOUtils.copyAndCloseInput(transformedReader, origWriter, IOUtils.DEFAULT_BUFFER_SIZE);
                } catch (IOException e) {
                    throw new Fault("READER_COPY", LOG, e, e.getMessage());
                } finally {
                    try {
                        origWriter.close();
                    } catch (IOException e) {
                        LOG.warning("Cannot close stream after transformation: " + e.getMessage());
                    }
                }
            }
        }

}