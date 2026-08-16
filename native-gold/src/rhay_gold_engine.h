#pragma once
#include <cstddef>
#include <string>
#include <vector>

namespace rhaygold {

// Rubber Band 3.3.0 / R3 Finer, exécuté hors ligne sur un clip déjà enregistré.
// ProcessRealTime est utilisé uniquement parce que la hauteur doit pouvoir varier
// pendant le rendu. Ce moteur ne doit jamais être branché au monitoring.
std::vector<float> processInterleaved(
    const float* input,
    std::size_t frames,
    int channels,
    int sampleRate,
    double pitchScale,
    std::string* error = nullptr);

// Version Auto-Tune continue : UNE SEULE instance Rubber Band pour tout le clip.
// pitchScales contient les ratios de hauteur successifs (1.0 = aucune correction),
// espacés de controlFrames images audio. Il n'y a ni découpage par note, ni reset
// du moteur, ni crossfade brut/tuné.
std::vector<float> processInterleavedCurve(
    const float* input,
    std::size_t frames,
    int channels,
    int sampleRate,
    const double* pitchScales,
    std::size_t scaleCount,
    std::size_t controlFrames,
    std::string* error = nullptr);

} // namespace rhaygold
