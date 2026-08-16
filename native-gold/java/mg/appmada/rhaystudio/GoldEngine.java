package mg.appmada.rhaystudio;

/**
 * Pont natif RHAY Auto-Tune CLEAN.
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

    /** Compatibilité : correction constante sur tout le tampon. */
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

    /**
     * Auto-Tune continu : une seule instance Rubber Band pour tout le clip.
     * pitchScales = ratios successifs (1.0 = hauteur inchangée).
     * controlFrames = nombre d'images audio entre deux valeurs de la courbe.
     */
    public static float[] processCurve(
            float[] interleaved,
            int channels,
            int sampleRate,
            double[] pitchScales,
            int controlFrames) {
        if (!AVAILABLE) {
            throw new IllegalStateException("RHAY GOLD natif indisponible: " + LOAD_ERROR);
        }
        if (pitchScales == null || pitchScales.length == 0 || controlFrames <= 0) {
            throw new IllegalArgumentException("Courbe Auto-Tune invalide");
        }
        return nativeProcessCurve(interleaved, channels, sampleRate, pitchScales, controlFrames);
    }

    private static native float[] nativeProcessRegion(
            float[] interleaved,
            int channels,
            int sampleRate,
            double pitchScale);

    private static native float[] nativeProcessCurve(
            float[] interleaved,
            int channels,
            int sampleRate,
            double[] pitchScales,
            int controlFrames);
}
