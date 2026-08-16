package mg.appmada.rhaystudio;

/**
 * Pont natif RHAY Auto-Tune CLEAN GOLD.
 *
 * Le moteur ne doit être appelé que sur un clip déjà enregistré.
 * Il ne doit jamais être branché sur AudioRecord, AudioTrack ou le monitoring.
 */
public final class GoldEngine {
    static {
        System.loadLibrary("rhaygold");
    }

    private GoldEngine() {}

    /**
     * @param interleaved PCM float -1..+1, interleaved par canal
     * @param channels nombre de canaux
     * @param sampleRate fréquence d'échantillonnage
     * @param pitchScale facteur Rubber Band (1.0 = hauteur inchangée)
     * @return nouveau buffer PCM de même longueur
     */
    public static native float[] processRegion(
            float[] interleaved,
            int channels,
            int sampleRate,
            double pitchScale);
}
