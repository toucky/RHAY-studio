#pragma once
#include <cstddef>
#include <string>
#include <vector>

namespace rhaygold {

// Moteur GOLD: Rubber Band 3.3.0, algorithme ProcessRealTime exécuté
// hors ligne. Ce choix reproduit le comportement du filtre utilisé pour
// le rendu GOLD, sans placer le DSP dans le monitoring.
std::vector<float> processInterleaved(
    const float* input,
    std::size_t frames,
    int channels,
    int sampleRate,
    double pitchScale,
    std::string* error = nullptr);

} // namespace rhaygold
