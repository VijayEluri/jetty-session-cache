/*
 * Copyright 2009 Victor Igumnov <victori@fabulously40.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.base;

import org.mortbay.component.AbstractLifeCycle;
import org.mortbay.jetty.HttpHeaders;
import org.mortbay.jetty.Request;
import org.mortbay.jetty.RequestLog;
import org.mortbay.jetty.Response;
import org.mortbay.jetty.servlet.PathMap;
import org.mortbay.log.Log;
import org.mortbay.util.*;

import javax.servlet.http.Cookie;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.*;

public class RequestMapLogger extends AbstractLifeCycle implements RequestLog {
    private String _filename;
    private boolean _extended;
    private boolean _append;
    private int _retainDays;
    private boolean _closeOut;
    private boolean _preferProxiedForAddress;
    private String _logDateFormat = "dd/MMM/yyyy:HH:mm:ss Z";
    private String _filenameDateFormat = null;
    private Locale _logLocale = Locale.getDefault();
    private String _logTimeZone = "GMT";
    private String[] _ignorePaths;
    private boolean _logLatency = false;
    private boolean _logCookies = false;
    private boolean _logServer = false;

    private transient OutputStream _out;
    private transient OutputStream _fileOut;
    private transient DateCache _logDateCache;
    private transient PathMap _ignorePathMap;
    private transient Writer _writer;
    private transient ArrayList _buffers;
    private transient char[] _copy;

    public final static String REQUEST_MAP = "request_map";


    public RequestMapLogger() {
        _extended = true;
        _append = true;
        _retainDays = 31;
        _preferProxiedForAddress = true;
    }

    public RequestMapLogger(String filename) {
        _extended = true;
        _append = true;
        _retainDays = 31;
        _preferProxiedForAddress = true;
        setFilename(filename);
    }

    public void setFilename(String filename) {
        if (filename != null) {
            filename = filename.trim();
            if (filename.length() == 0) {
                filename = null;
            }
        }
        _filename = filename;
    }

    public String getFilename() {
        return _filename;
    }

    public Writer get_writer() {
        return _writer;
    }

    public String getDatedFilename() {
        if (_fileOut instanceof RolloverFileOutputStream) {
            return ((RolloverFileOutputStream) _fileOut).getDatedFilename();
        }
        return null;
    }

    public void setLogDateFormat(String format) {
        _logDateFormat = format;
    }

    public String getLogDateFormat() {
        return _logDateFormat;
    }

    public void setLogLocale(Locale logLocale) {
        _logLocale = logLocale;
    }

    public Locale getLogLocale() {
        return _logLocale;
    }

    public void setLogTimeZone(String tz) {
        _logTimeZone = tz;
    }

    public String getLogTimeZone() {
        return _logTimeZone;
    }

    public void setRetainDays(int retainDays) {
        _retainDays = retainDays;
    }

    public int getRetainDays() {
        return _retainDays;
    }

    public void setExtended(boolean extended) {
        _extended = extended;
    }

    public boolean isExtended() {
        return _extended;
    }

    public void setAppend(boolean append) {
        _append = append;
    }

    public boolean isAppend() {
        return _append;
    }

    public void setIgnorePaths(String[] ignorePaths) {
        _ignorePaths = ignorePaths;
    }

    public String[] getIgnorePaths() {
        return _ignorePaths;
    }

    public void setLogCookies(boolean logCookies) {
        _logCookies = logCookies;
    }

    public boolean getLogCookies() {
        return _logCookies;
    }

    public boolean getLogServer() {
        return _logServer;
    }

    public void setLogServer(boolean logServer) {
        _logServer = logServer;
    }

    public void setLogLatency(boolean logLatency) {
        _logLatency = logLatency;
    }

    public boolean getLogLatency() {
        return _logLatency;
    }

    public void setPreferProxiedForAddress(boolean preferProxiedForAddress) {
        _preferProxiedForAddress = preferProxiedForAddress;
    }

    public void log(Request request, Response response) {
        if (!isStarted()) {
            return;
        }

        try {
            if (_ignorePathMap != null && _ignorePathMap.getMatch(request.getRequestURI()) != null) {
                return;
            }

            if (_fileOut == null) {
                return;
            }

            Utf8StringBuffer u8buf;
            StringBuffer buf;
            synchronized (_writer) {
                int size = _buffers.size();
                u8buf = size == 0 ? new Utf8StringBuffer(160) : (Utf8StringBuffer) _buffers.remove(size - 1);
                buf = u8buf.getStringBuffer();
            }

            synchronized (buf) // for efficiency until we can use StringBuilder
            {
                if (_logServer) {
                    buf.append(request.getServerName());
                    buf.append(' ');
                }

                String addr = null;
                if (_preferProxiedForAddress) {
                    addr = request.getHeader(HttpHeaders.X_FORWARDED_FOR);
                    if (request.getHeader("X-Real-IP") != null) {
                        addr = request.getHeader("X-Real-IP");
                    }
                }

                if (addr == null) {
                    addr = request.getRemoteAddr();
                }

                buf.append(addr);
                buf.append(" - ");
                String user = request.getRemoteUser();
                buf.append((user == null) ? "-" : user);
                buf.append(" [");
                if (_logDateCache != null) {
                    buf.append(_logDateCache.format(request.getTimeStamp()));
                } else {
                    buf.append(request.getTimeStampBuffer().toString());
                }

                buf.append("] \"");
                buf.append(request.getMethod());
                buf.append(' ');

                request.getUri().writeTo(u8buf);

                buf.append(' ');
                buf.append(request.getProtocol());
                buf.append("\" ");
                int status = response.getStatus();
                if (status <= 0) {
                    status = 404;
                }
                buf.append((char) ('0' + ((status / 100) % 10)));
                buf.append((char) ('0' + ((status / 10) % 10)));
                buf.append((char) ('0' + (status % 10)));


                long responseLength = response.getContentCount();
                if (responseLength >= 0) {
                    buf.append(' ');
                    if (responseLength > 99999) {
                        buf.append(Long.toString(responseLength));
                    } else {
                        if (responseLength > 9999) {
                            buf.append((char) ('0' + ((responseLength / 10000) % 10)));
                        }
                        if (responseLength > 999) {
                            buf.append((char) ('0' + ((responseLength / 1000) % 10)));
                        }
                        if (responseLength > 99) {
                            buf.append((char) ('0' + ((responseLength / 100) % 10)));
                        }
                        if (responseLength > 9) {
                            buf.append((char) ('0' + ((responseLength / 10) % 10)));
                        }
                        buf.append((char) ('0' + (responseLength) % 10));
                    }
                    buf.append(' ');
                } else {
                    buf.append(" - ");
                }

            }

            if (!_extended && !_logCookies && !_logLatency) {
                synchronized (_writer) {
                    buf.append(StringUtil.__LINE_SEPARATOR);
                    int l = buf.length();
                    if (l > _copy.length) {
                        l = _copy.length;
                    }
                    buf.getChars(0, l, _copy, 0);
                    _writer.write(_copy, 0, l);
                    _writer.flush();
                    u8buf.reset();
                    _buffers.add(u8buf);
                }
            } else {
                synchronized (_writer) {
                    int l = buf.length();
                    if (l > _copy.length) {
                        l = _copy.length;
                    }
                    buf.getChars(0, l, _copy, 0);
                    _writer.write(_copy, 0, l);
                    u8buf.reset();
                    _buffers.add(u8buf);

                    // TODO do outside synchronized scope
                    if (_extended) {
                        logExtended(request, response, _writer);
                    }

                    // TODO do outside synchronized scope
                    if (_logCookies) {
                        Cookie[] cookies = request.getCookies();
                        if (cookies == null || cookies.length == 0) {
                            _writer.write(" -");
                        } else {
                            _writer.write(" \"");
                            for (int i = 0; i < cookies.length; i++) {
                                if (i != 0) {
                                    _writer.write(';');
                                }
                                _writer.write(cookies[i].getName());
                                _writer.write('=');
                                _writer.write(cookies[i].getValue());
                            }
                            _writer.write('\"');
                        }
                    }

                    if (_logLatency) {
                        _writer.write(' ');
                        _writer.write(TypeUtil.toString(System.currentTimeMillis() - request.getTimeStamp()));
                    }

                    _writer.write(StringUtil.__LINE_SEPARATOR);
                    _writer.flush();
                }
            }
        } catch (IOException e) {
            Log.warn(e);
        }

    }

    protected void logExtended(Request request,
                               Response response,
                               Writer writer) throws IOException {
        String referer = request.getHeader(HttpHeaders.REFERER);
        if (referer == null) {
            writer.write("\"-\" ");
        } else {
            writer.write('"');
            writer.write(referer);
            writer.write("\" ");
        }

        String agent = request.getHeader(HttpHeaders.USER_AGENT);
        if (agent == null) {
            writer.write("\"-\" ");
        } else {
            writer.write('"');
            writer.write(agent);
            writer.write('"');
        }

        if (request.getAttribute(REQUEST_MAP) == null) {
            request.setAttribute(REQUEST_MAP, new HashMap());
        }

        String host = request.getConnection().getConnector().getHost();
        int port = request.getConnection().getConnector().getLocalPort();
        if (host == null) {
            host = request.getLocalAddr();
        }

        Map<Object, Object> req = (Map) request.getAttribute(REQUEST_MAP);
        req.put("req_time", TypeUtil.toString(System.currentTimeMillis() - request.getTimeStamp()));
        req.put("serv_host", String.format("%s:%s", host, port));

        StringBuilder accessLog = new StringBuilder();
        for (Map.Entry<Object, Object> e : req.entrySet()) {
            accessLog.append("!");
            accessLog.append(e.getKey());
            accessLog.append("=");
            accessLog.append(e.getValue());
        }
        if (req.size() > 0) {
            writer.write(" ");
            writer.write(accessLog.substring(1));
        }
    }

    protected void doStart() throws Exception {
        if (_logDateFormat != null) {
            _logDateCache = new DateCache(_logDateFormat, _logLocale);
            _logDateCache.setTimeZoneID(_logTimeZone);
        }

        if (_filename != null) {
            _fileOut = new RolloverFileOutputStream(_filename, _append, _retainDays, TimeZone.getTimeZone(_logTimeZone),
                    _filenameDateFormat, null);
            _closeOut = true;
            Log.info("Opened " + getDatedFilename());
        } else {
            _fileOut = System.err;
        }

        _out = _fileOut;

        if (_ignorePaths != null && _ignorePaths.length > 0) {
            _ignorePathMap = new PathMap();
            for (int i = 0; i < _ignorePaths.length; i++) {
                _ignorePathMap.put(_ignorePaths[i], _ignorePaths[i]);
            }
        } else {
            _ignorePathMap = null;
        }

        _writer = new OutputStreamWriter(_out);
        _buffers = new ArrayList();
        _copy = new char[1024];
        super.doStart();
    }

    /* ------------------------------------------------------------ */

    protected void doStop() throws Exception {
        super.doStop();
        try {
            if (_writer != null) {
                _writer.flush();
            }
        } catch (IOException e) {
            Log.ignore(e);
        }
        if (_out != null && _closeOut) {
            try {
                _out.close();
            } catch (IOException e) {
                Log.ignore(e);
            }
        }

        _out = null;
        _fileOut = null;
        _closeOut = false;
        _logDateCache = null;
        _writer = null;
        _buffers = null;
        _copy = null;
    }

    public String getFilenameDateFormat() {
        return _filenameDateFormat;
    }

    public void setFilenameDateFormat(String logFileDateFormat) {
        _filenameDateFormat = logFileDateFormat;
    }
}
