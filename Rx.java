import java.util.ArrayList;

/**
 * Receiver: reads PCM symbol windows from a LoopbackBus.Channel, detects the
 * dominant 4-FSK tone via Goertzel, streams raw symbols and a running byte
 * stream (every 4 symbols), and also parses full packets (LEN+payload+CRC).
 *
 * Real-time behavior:
 *  - onSymbol(sym) fires for EVERY symbol observed (including preamble/sync).
 *  - onByteProgress(bytesSoFar) fires whenever the total symbol count is a
 *    multiple of 4; it represents the raw stream grouped into bytes so far.
 *  - onPacket(payload) fires when a well-formed outer packet passes CRC.
 */
public final class Rx implements Runnable {

    public interface Listener {
        void onSymbol(int symIndex);                // 0..3 each symbol window
        void onByteProgress(byte[] bytesSoFar);     // running raw bytes stream
        void onPacket(byte[] payload);              // decoded packet (after CRC)
    }

    private final LoopbackBus.Channel ch;
    private final Listener listener;
    private volatile boolean running = true;

    private final int symFrames = (int)(Tone.SR * Fsk.SYMBOL_MS / 1000.0);
    private final int symBytes  = symFrames * Tone.BYTES;

    private final Goertzel[] bands = new Goertzel[] {
        new Goertzel(Fsk.FREQ[0], symFrames, Tone.SR),
        new Goertzel(Fsk.FREQ[1], symFrames, Tone.SR),
        new Goertzel(Fsk.FREQ[2], symFrames, Tone.SR),
        new Goertzel(Fsk.FREQ[3], symFrames, Tone.SR)
    };

    public Rx(LoopbackBus.Channel ch, Listener l) {
        this.ch = ch;
        this.listener = l;
    }

    public void stop(){ running = false; }

    @Override public void run() {
        byte[] buf = new byte[symBytes];

        // For packet detection
        int preambleRun = 0;
        boolean synced = false;
        ArrayList<Integer> bodySyms = new ArrayList<>();

        // For live byte stream (raw, from the start of the session)
        ArrayList<Integer> streamSyms = new ArrayList<>();

        while (running) {
            int got = ch.read(buf);
            if (got != symBytes) break;

            int sym = detect(buf);

            // ALWAYS report raw symbol stream
            if (listener != null) listener.onSymbol(sym);

            // Accumulate into the raw stream and emit bytes every 4 symbols
            streamSyms.add(sym);
            if (listener != null && (streamSyms.size() % 4 == 0)) {
                byte[] bytesSoFar = Fsk.symbolsToBytes(streamSyms.stream().mapToInt(i->i).toArray());
                listener.onByteProgress(bytesSoFar);
            }

            // --- Packet sync/detect path (unchanged behavior) ---
            if (!synced) {
                // detect alternating 0/2 preamble
                if ((preambleRun % 2 == 0 && sym == 0) || (preambleRun % 2 == 1 && sym == 2)) {
                    preambleRun++;
                } else {
                    preambleRun = (sym == 0) ? 1 : 0;
                }

                if (preambleRun >= Fsk.PREAMBLE_SYMS) {
                    // Expect the 3-symbol sync
                    int s1 = next(buf), s2 = next(buf), s3 = next(buf);

                    // Also stream those symbols live
                    if (listener != null) { listener.onSymbol(s1); listener.onSymbol(s2); listener.onSymbol(s3); }

                    // And add them to the raw stream + byte progress
                    streamSyms.add(s1); streamSyms.add(s2); streamSyms.add(s3);
                    if (listener != null) {
                        // emit byte progress for any new completed bytes
                        int rem = streamSyms.size() % 4;
                        if (rem == 0) {
                            byte[] bytesSoFar = Fsk.symbolsToBytes(streamSyms.stream().mapToInt(i->i).toArray());
                            listener.onByteProgress(bytesSoFar);
                        }
                    }

                    if (s1 == Fsk.SYNC0 && s2 == Fsk.SYNC1 && s3 == Fsk.SYNC2) {
                        synced = true;
                        bodySyms.clear();
                    } else {
                        preambleRun = 0;
                    }
                }
                continue;
            }

            // Synced: collect body symbols
            bodySyms.add(sym);

            // Try to complete a packet
            if (bodySyms.size() >= 4) {
                byte[] head = Fsk.symbolsToBytes(bodySyms.stream().mapToInt(i->i).toArray());
                int len = head[0] & 0xFF;
                int totalBytes = 1 + len + 2;   // LEN + payload + CRC
                int needSyms   = totalBytes * 4;

                if (bodySyms.size() == needSyms) {
                    byte[] pkt = Fsk.symbolsToBytes(bodySyms.stream().mapToInt(i->i).toArray());
                    byte[] payload = Packetizer.tryParse(pkt);
                    if (payload != null && listener != null) listener.onPacket(payload);

                    // Reset for next packet
                    preambleRun = 0; synced = false; bodySyms.clear();
                } else if (bodySyms.size() > needSyms) {
                    // desync; reset and try again
                    preambleRun = 0; synced = false; bodySyms.clear();
                }
            }
        }
    }

    private int next(byte[] reuse){
        int got = ch.read(reuse);
        if (got != symBytes) return -1;
        return detect(reuse);
    }

    private int detect(byte[] frameBytes){
        int N = frameBytes.length/2;
        for (Goertzel g : bands) g.reset();
        for (int i=0;i<N;i++){
            int lo = frameBytes[i*2] & 0xFF;
            int hi = frameBytes[i*2+1] & 0xFF;
            short s = (short)((hi<<8)|lo);
            double v = s / 32768.0;
            for (Goertzel g : bands) g.push(v);
        }
        double best = -1; int idx = 0;
        for (int i=0;i<bands.length;i++){
            double p = bands[i].power();
            if (p > best) { best = p; idx = i; }
        }
        return idx;
    }

    /** Fixed-bin Goertzel for a known window size. */
    static final class Goertzel {
        private final double coeff;
        private double s0=0, s1=0, s2=0;
        Goertzel(double freq, int N, float sr){
            double k = Math.round( (N * freq) / sr );
            double w = (2*Math.PI / N) * k;
            this.coeff = 2 * Math.cos(w);
        }
        void reset(){ s0=s1=s2=0; }
        void push(double x){ s0 = x + coeff*s1 - s2; s2 = s1; s1 = s0; }
        double power(){ return s1*s1 + s2*s2 - coeff*s1*s2; }
    }
}
