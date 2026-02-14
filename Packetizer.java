import java.util.Arrays;

/** Outer packet: [LEN(1)] [PAYLOAD(len)] [CRC16(2)] */
public final class Packetizer {
    private Packetizer(){}
    public static byte[] makePacket(byte[] payload){
        int len = Math.min(255, payload.length);
        byte[] body = new byte[1+len];
        body[0]=(byte)len;
        System.arraycopy(payload,0,body,1,len);
        int crc = Crc16.ccitt(body,0,body.length);
        byte[] pkt = Arrays.copyOf(body, body.length+2);
        pkt[pkt.length-2] = (byte)((crc>>>8)&0xFF);
        pkt[pkt.length-1] = (byte)(crc&0xFF);
        return pkt;
    }
    public static byte[] tryParse(byte[] pkt){
        if (pkt.length<3) return null;
        int len = pkt[0]&0xFF;
        if (pkt.length != 1+len+2) return null;
        int got  = ((pkt[pkt.length-2]&0xFF)<<8) | (pkt[pkt.length-1]&0xFF);
        int calc = Crc16.ccitt(pkt,0,pkt.length-2);
        if (got!=calc) return null;
        return Arrays.copyOfRange(pkt,1,1+len);
    }
}
