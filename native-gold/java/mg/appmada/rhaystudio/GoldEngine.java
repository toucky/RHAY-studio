package mg.appmada.rhaystudio;

/**
 * Pont natif RHAY Auto-Tune CLEAN GOLD.
 *
 * Le moteur ne doit être appelé que sur un clip déjà enregistré.
 * Il ne doit jamais être branché sur AudioRecord, AudioTrack ou le monitoring.
 */
public final class GoldEngine {
    private static final boolean AVAILABLE;
    private static final String LOAD_ERROR;

    static {
        boolean ok = false;
        String err = "";
        try {
            System.loadLibrary("rhaygold");
            ok = true;
        } catch (Throwable t) {
            err = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
        }
        AVAILABLE = ok;
        LOAD_ERROR = err;
    }

    private GoldEngine() {}

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static String loadError() {
        return LOAD_ERROR;
    }

    public static float[] processRegion(
            float[] interleaved,
            int channels,
            int sampleRate,
            double pitchScale) {
        if (!AVAILABLE) {
            throw new IllegalStateException("RHAY GOLD natif indisponible: " + LOAD_ERROR);
        }
        return nativeProcessRegion(interleaved, channels, sampleRate, pitchScale);
    }

    private static native float[] nativeProcessRegion(
            float[] interleaved,
            int channels,
            int sampleRate,
            double pitchScale);
}
