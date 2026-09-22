package com.github.lisama569.devicegatewayjava.integration;

import com.github.lisama569.devicegatewayjava.auth.Authenticator;
import com.github.lisama569.devicegatewayjava.config.GatewayProperties;
import com.github.lisama569.devicegatewayjava.delivery.GatewayClock;
import com.github.lisama569.devicegatewayjava.delivery.ManualClock;
import com.github.lisama569.devicegatewayjava.gateway.ConnectionHandler;
import com.github.lisama569.devicegatewayjava.gateway.GatewayMetrics;
import com.github.lisama569.devicegatewayjava.gateway.GatewayServer;
import com.github.lisama569.devicegatewayjava.session.DeviceStateStore;
import com.github.lisama569.devicegatewayjava.session.SessionRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Boots the real TCP stack on 127.0.0.1:0 (ephemeral port) with a
 * {@link ManualClock}, so retry/timeout behaviour is deterministic and needs
 * no real sleeps or external services.
 */
final class TestGateway {

    final GatewayProperties props;
    final ManualClock clock;
    final GatewayServer server;
    final GatewayMetrics metrics;
    final SessionRegistry sessions;
    final DeviceStateStore deviceStates;
    final ManualRetryScheduler retryScheduler;

    private TestGateway(GatewayProperties props, ManualClock clock, GatewayServer server,
                        GatewayMetrics metrics, SessionRegistry sessions,
                        DeviceStateStore deviceStates,
                        ManualRetryScheduler retryScheduler) {
        this.props = props;
        this.clock = clock;
        this.server = server;
        this.metrics = metrics;
        this.sessions = sessions;
        this.deviceStates = deviceStates;
        this.retryScheduler = retryScheduler;
    }

    static TestGateway start() throws IOException {
        return start(defaultProps());
    }

    static GatewayProperties defaultProps() {
        GatewayProperties p = new GatewayProperties();
        p.setBindHost("127.0.0.1");
        p.setPort(0);
        p.setMaxConnections(4);
        p.setMaxFramePayload(256);
        p.setMaxBufferedBytes(1 << 16);
        p.setMaxOutboundPending(8);
        p.setMaxRetries(3);
        p.setRetryIntervalMillis(200);
        p.setDownlinkTimeoutMillis(1000);
        p.getCredentials().put("dev-001", credential("tok_valid_001", 4102444800000L));
        p.getCredentials().put("dev-expired", credential("tok_expired", 1L));
        return p;
    }

    private static GatewayProperties.Credential credential(String token, long expiresAt) {
        GatewayProperties.Credential c = new GatewayProperties.Credential();
        c.setToken(token);
        c.setExpiresAt(expiresAt);
        return c;
    }

    static TestGateway start(GatewayProperties props) throws IOException {
        ManualClock clock = new ManualClock(1_000_000L);
        GatewayMetrics metrics = new GatewayMetrics();
        SessionRegistry sessions = new SessionRegistry();
        DeviceStateStore deviceStates = new DeviceStateStore();
        Authenticator authenticator = new Authenticator(
                new com.github.lisama569.devicegatewayjava.auth.ConfigCredentialStore(props));
        ManualRetryScheduler retryScheduler = new ManualRetryScheduler(1_000_000L);
        GatewayServer server = new GatewayServer(props, authenticator, sessions,
                deviceStates, metrics, clock, retryScheduler);
        server.start();
        return new TestGateway(props, clock, server, metrics, sessions, deviceStates,
                retryScheduler);
    }

    void stop() {
        server.stop();
    }

    Socket connect() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", server.boundPort()), 1000);
        socket.setSoTimeout(2000);
        return socket;
    }

    /** A fully logged-in device client. */
    DeviceClient loginClient(String deviceId, String token) throws IOException {
        DeviceClient client = new DeviceClient(connect());
        client.helloAndLogin(1, deviceId, token);
        return client;
    }

    /** A frame-reading loopback client with explicit chunk writes. */
    final class DeviceClient implements AutoCloseable {
        final Socket socket;
        final java.io.OutputStream out;
        final java.io.InputStream in;
        long nextDeviceSeq = 1;

        static DeviceClient create(TestGateway gw, Socket socket) throws IOException {
            return gw.new DeviceClient(socket);
        }

        DeviceClient(Socket socket) throws IOException {
            this.socket = socket;
            this.out = socket.getOutputStream();
            this.in = socket.getInputStream();
        }

        void helloAndLogin(int version, String deviceId, String token) throws IOException {
            sendRaw(com.github.lisama569.devicegatewayjava.protocol.FrameEncoder.encode(
                    new com.github.lisama569.devicegatewayjava.protocol.Frame(
                            com.github.lisama569.devicegatewayjava.protocol.Frame.T_HELLO,
                            (byte) 0, 0,
                            com.github.lisama569.devicegatewayjava.protocol.Messages.hello(
                                    version, version))));
            com.github.lisama569.devicegatewayjava.protocol.Frame helloAck = readFrame();
            if (helloAck.type() != com.github.lisama569.devicegatewayjava.protocol.Frame.T_HELLO_ACK) {
                throw new IOException("expected HELLO_ACK got type " + helloAck.type());
            }
            sendRaw(com.github.lisama569.devicegatewayjava.protocol.FrameEncoder.encode(
                    new com.github.lisama569.devicegatewayjava.protocol.Frame(
                            com.github.lisama569.devicegatewayjava.protocol.Frame.T_LOGIN,
                            (byte) 0, 0,
                            com.github.lisama569.devicegatewayjava.protocol.Messages.login(
                                    deviceId, token, clock.nowMillis()))));
            com.github.lisama569.devicegatewayjava.protocol.Frame loginAck = readFrame();
            if (loginAck.type() != com.github.lisama569.devicegatewayjava.protocol.Frame.T_LOGIN_ACK) {
                throw new IOException("expected LOGIN_ACK got " + loginAck.type());
            }
        }

        void sendRaw(byte[] data) throws IOException {
            out.write(data);
            out.flush();
        }

        void sendRawChunks(byte[] data, int chunkSize) throws IOException {
            for (int off = 0; off < data.length; off += chunkSize) {
                int n = Math.min(chunkSize, data.length - off);
                out.write(data, off, n);
                out.flush();
            }
        }

        long nextSeq() {
            return nextDeviceSeq++;
        }

        com.github.lisama569.devicegatewayjava.protocol.Frame uplink(String key, long value)
                throws IOException {
            long seq = nextSeq();
            sendRaw(com.github.lisama569.devicegatewayjava.protocol.FrameEncoder.encode(
                    new com.github.lisama569.devicegatewayjava.protocol.Frame(
                            com.github.lisama569.devicegatewayjava.protocol.Frame.T_UPLINK,
                            (byte) 0, seq,
                            com.github.lisama569.devicegatewayjava.protocol.Messages.uplink(key, value))));
            return readFrame();
        }

        com.github.lisama569.devicegatewayjava.protocol.Frame readFrame() throws IOException {
            byte[] header = readN(com.github.lisama569.devicegatewayjava.protocol.Frame.HEADER_LEN);
            long len = ((long) (header[9] & 0xFF) << 24) | ((header[10] & 0xFF) << 16)
                    | ((header[11] & 0xFF) << 8) | (header[12] & 0xFF);
            byte[] rest = readN((int) len + com.github.lisama569.devicegatewayjava.protocol.Frame.CRC_LEN);
            byte[] full = new byte[header.length + rest.length];
            System.arraycopy(header, 0, full, 0, header.length);
            System.arraycopy(rest, 0, full, header.length, rest.length);
            // decode server frame through the same decoder for CRC validation
            com.github.lisama569.devicegatewayjava.protocol.FrameDecoder d =
                    new com.github.lisama569.devicegatewayjava.protocol.FrameDecoder(
                            1 << 20, 1 << 22);
            d.append(full, 0, full.length);
            com.github.lisama569.devicegatewayjava.protocol.DecodeResult r = d.decode();
            if (r instanceof com.github.lisama569.devicegatewayjava.protocol.DecodeResult.FrameOk ok) {
                return ok.frame();
            }
            throw new IOException("bad server frame: " + r);
        }

        List<com.github.lisama569.devicegatewayjava.protocol.Frame> drainFrames(int maxWaitMillis)
                throws IOException {
            socket.setSoTimeout(Math.min(maxWaitMillis, 100));
            List<com.github.lisama569.devicegatewayjava.protocol.Frame> frames = new ArrayList<>();
            try {
                while (true) {
                    frames.add(readFrame());
                }
            } catch (java.net.SocketTimeoutException expected) {
                // no more frames currently available
            } catch (IOException eofOrReset) {
                // peer closed the session (e.g. terminated) - treat as end of stream
            } finally {
                socket.setSoTimeout(2000);
            }
            return frames;
        }

        private byte[] readN(int n) throws IOException {
            byte[] b = new byte[n];
            int off = 0;
            while (off < n) {
                int r = in.read(b, off, n - off);
                if (r < 0) {
                    throw new IOException("EOF after " + off + " of " + n + " bytes");
                }
                off += r;
            }
            return b;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
