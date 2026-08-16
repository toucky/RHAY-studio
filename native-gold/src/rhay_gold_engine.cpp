#include "rhay_gold_engine.h"
#include <rubberband/rubberband-c.h>

#include <algorithm>
#include <cmath>
#include <vector>

namespace {
constexpr std::size_t kBlock = 2048;

constexpr RubberBandOptions kGoldOptions =
    RubberBandOptionProcessRealTime |
    RubberBandOptionTransientsSmooth |
    RubberBandOptionDetectorSoft |
    RubberBandOptionPhaseLaminar |
    RubberBandOptionThreadingNever |
    RubberBandOptionWindowLong |
    RubberBandOptionSmoothingOn |
    RubberBandOptionFormantPreserved |
    RubberBandOptionPitchHighConsistency |
    RubberBandOptionChannelsTogether;
}

namespace rhaygold {

std::vector<float> processInterleaved(const float* input,
                                      std::size_t frames,
                                      int channels,
                                      int sampleRate,
                                      double pitchScale,
                                      std::string* error) {
    if (!input || frames == 0 || channels < 1 || channels > 8 ||
        sampleRate < 8000 || !std::isfinite(pitchScale) ||
        pitchScale < 0.25 || pitchScale > 4.0) {
        if (error) *error = "invalid arguments";
        return {};
    }

    RubberBandState st = rubberband_new(
        static_cast<unsigned>(sampleRate),
        static_cast<unsigned>(channels),
        kGoldOptions,
        1.0,
        pitchScale);

    if (!st) {
        if (error) *error = "rubberband_new failed";
        return {};
    }

    // Important : ProcessRealTime décrit l'algorithme Rubber Band utilisé
    // pour le GOLD. RHAY l'exécute cependant hors ligne sur un clip déjà
    // enregistré : aucune connexion au monitoring.
    rubberband_set_expected_input_duration(st, static_cast<unsigned>(frames));

    std::vector<std::vector<float>> planar(
        channels, std::vector<float>(frames));
    for (std::size_t i = 0; i < frames; ++i) {
        for (int c = 0; c < channels; ++c) {
            planar[c][i] = input[i * channels + c];
        }
    }

    std::vector<std::vector<float>> outPlanar(channels);
    for (auto& channel : outPlanar) channel.reserve(frames + sampleRate);

    std::vector<std::vector<float>> temp(
        channels, std::vector<float>(8192));
    std::vector<float*> tempPtrs(channels);
    for (int c = 0; c < channels; ++c) tempPtrs[c] = temp[c].data();

    auto drain = [&]() {
        while (true) {
            int available = rubberband_available(st);
            if (available <= 0) break;
            unsigned want = static_cast<unsigned>(std::min(available, 8192));
            unsigned got = rubberband_retrieve(st, tempPtrs.data(), want);
            if (!got) break;
            for (int c = 0; c < channels; ++c) {
                outPlanar[c].insert(
                    outPlanar[c].end(), temp[c].begin(), temp[c].begin() + got);
            }
        }
    };

    for (std::size_t pos = 0; pos < frames; pos += kBlock) {
        unsigned count = static_cast<unsigned>(
            std::min<std::size_t>(kBlock, frames - pos));
        std::vector<const float*> ptrs(channels);
        for (int c = 0; c < channels; ++c) {
            ptrs[c] = planar[c].data() + pos;
        }
        rubberband_process(
            st, ptrs.data(), count, pos + count == frames ? 1 : 0);
        drain();
    }
    drain();
    rubberband_delete(st);

    if (outPlanar.empty() || outPlanar[0].empty()) {
        if (error) *error = "no output";
        return {};
    }

    // Contrat RHAY : la durée du clip ne change jamais.
    std::vector<float> output(frames * channels, 0.0f);
    const std::size_t produced = outPlanar[0].size();
    const std::size_t copyFrames = std::min(frames, produced);
    for (std::size_t i = 0; i < copyFrames; ++i) {
        for (int c = 0; c < channels; ++c) {
            output[i * channels + c] = outPlanar[c][i];
        }
    }
    return output;
}

} // namespace rhaygold
