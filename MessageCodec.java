import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class MessageCodec {
    private MessageCodec(){}

    // payload: [SRC][DST][TYPE][LEN][DATA...]
    // much more natural: first byte = who sent this
    public static final int TYPE_DIRECT   = 1;
    public static final int TYPE_ANNOUNCE = 2;
    public static final int DST_BROADCAST = 0xFF;

    public static byte[] encode(int src, int dst, int type, byte[] data) {
        int len = Math.min(255, data.length);
        byte[] p = new byte[4 + len];
        p[0] = (byte)(src & 0xFF);   // who sends
        p[1] = (byte)(dst & 0xFF);   // who should get it
        p[2] = (byte)(type & 0xFF);  // direct/announce
        p[3] = (byte)(len & 0xFF);   // length of text
        System.arraycopy(data, 0, p, 4, len);
        return p;
    }

    public static Decoded decode(byte[] payload) {
        if (payload.length < 4) return null;
        int src = payload[0] & 0xFF;
        int dst = payload[1] & 0xFF;
        int type = payload[2] & 0xFF;
        int len = payload[3] & 0xFF;
        if (payload.length != 4 + len) return null;
        byte[] data = Arrays.copyOfRange(payload, 4, 4 + len);
        return new Decoded(src, dst, type, data);
    }

    public static byte[] text(String s){ return s.getBytes(StandardCharsets.UTF_8); }
    public static String toText(byte[] b){ return new String(b, StandardCharsets.UTF_8); }

    public record Decoded(int src, int dst, int type, byte[] data) {}
}
