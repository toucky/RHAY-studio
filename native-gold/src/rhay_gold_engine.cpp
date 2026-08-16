#include "rhay_gold_engine.h"
#include <rubberband/rubberband-c.h>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <vector>

namespace {

constexpr RubberBandOptions kGoldOptions =
    RubberBandOptionProcessRealTime |
    RubberBandOptionTransientsSmooth |
    RubberBandOptionDetectorSoft |
    RubberBandOptionPhaseLaminar |
    RubberBandOptionThreadingNever |
    RubberBandOptionWindowLong |
    RubberBandOptionFormantPreserved |
    RubberBandOptionPitchHighConsistency |
    RubberBandOptionChannelsTogether |
    RubberBandOptionEngineFiner;

inline bool validScale(double v) {
    return std::isfinite(v) && v >= 0.25 && v <= 4.0;
}

} // namespace

namespace rhaygold {

std::vector<float> processInterleavedCurve(
    const float* input,
    std::size_t frames,
    int channels,
    int sampleRate,
    const double* pitchScales,
    std::size_t scaleCount,
    std::size_t controlFrames,
    std::string* error) {

    if (!input || frames == 0 || channels < 1 || channels > 8 ||
        sampleRate < 8000 || !pitchScales || scaleCount == 0 ||
        controlFrames == 0) {
        if (error) *error = "invalid arguments";
        return {};
    }

    for (std::size_t i = 0; i < scaleCount; ++i) {
        if (!validScale(pitchScales[i])) {
            if (error) *error = "invalid pitch scale";
            return {};
        }
    }

    const double firstScale = pitchScales[0];
    RubberBandState st = rubberband_new(
        static_cast<unsigned>(sampleRate),
        static_cast<unsigned>(channels),
        kGoldOptions,
        1.0,
        firstScale);

    if (!st) {
        if (error) *error = "rubberband_new failed";
        return {};
    }

    // Le rendu reste hors ligne dans RHAY Studio, mais Rubber Band est en mode
    // ProcessRealTime afin d'autoriser setPitchScale() pendant une seule passe.
    // Le pad et le start delay sont compensés explicitement, comme recommandé
    // pour ce mode, afin de conserver exactement l'alignement temporel du clip.
    const std::size_t startPad = rubberband_get_preferred_start_pad(st);
    const std::size_t startDelay = rubberband_get_start_delay(st);
    const std::size_t processBlock = static_cast<std::size_t>(
        std::max(64, static_cast<int>(std::lround(sampleRate * 0.010))));

    rubberband_set_max_process_size(st, static_cast<unsigned>(processBlock));
    rubberband_set_expected_input_duration(
        st, static_cast<unsigned>(std::min<std::size_t>(
                startPad + frames, static_cast<std::size_t>(0xffffffffu))));

    std::vector<std::vector<float>> source(
        channels, std::vector<float>(frames));
    for (std::size_t i = 0; i < frames; ++i) {
        for (int c = 0; c < channels; ++c) {
            source[c][i] = input[i * static_cast<std::size_t>(channels) + c];
        }
    }

    std::vector<std::vector<float>> block(
        channels, std::vector<float>(processBlock, 0.0f));
    std::vector<const float*> inputPtrs(channels);

    constexpr std::size_t kRetrieve = 16384;
    std::vector<std::vector<float>> temp(
        channels, std::vector<float>(kRetrieve));
    std::vector<float*> tempPtrs(channels);
    for (int c = 0; c < channels; ++c) tempPtrs[c] = temp[c].data();

    std::vector<std::vector<float>> rendered(channels);
    for (auto& c : rendered) c.reserve(startPad + frames + sampleRate);

    auto drain = [&]() {
        while (true) {
            const int available = rubberband_available(st);
            if (available <= 0) break;
            const unsigned want = static_cast<unsigned>(
                std::min<std::size_t>(static_cast<std::size_t>(available), kRetrieve));
            const unsigned got = rubberband_retrieve(st, tempPtrs.data(), want);
            if (!got) break;
            for (int c = 0; c < channels; ++c) {
                rendered[c].insert(
                    rendered[c].end(), temp[c].begin(), temp[c].begin() + got);
            }
        }
    };

    const std::size_t totalInputFrames = startPad + frames;
    for (std::size_t pos = 0; pos < totalInputFrames; pos += processBlock) {
        const std::size_t count = std::min(processBlock, totalInputFrames - pos);

        // Le contrôle de hauteur est indexé sur le temps du clip, pas sur le pad.
        const std::size_t sourcePos = pos > startPad ? pos - startPad : 0;
        const std::size_t curveIndex = std::min(
            scaleCount - 1, sourcePos / controlFrames);
        const double scale = (pos < startPad) ? firstScale : pitchScales[curveIndex];
        rubberband_set_pitch_scale(st, scale);

        for (int c = 0; c < channels; ++c) {
            std::fill(block[c].begin(), block[c].begin() + count, 0.0f);
            for (std::size_t j = 0; j < count; ++j) {
                const std::size_t global = pos + j;
                if (global >= startPad) {
                    const std::size_t src = global - startPad;
                    if (src < frames) block[c][j] = source[c][src];
                }
            }
            inputPtrs[c] = block[c].data();
        }

        const int final = (pos + count >= totalInputFrames) ? 1 : 0;
        rubberband_process(
            st, inputPtrs.data(), static_cast<unsigned>(count), final);
        drain();
    }
    drain();
    rubberband_delete(st);

    if (rendered.empty() || rendered[0].empty()) {
        if (error) *error = "no output";
        return {};
    }

    // Un seul buffer final, même longueur que la source. Aucune superposition
    // brut/tuné et aucun crossfade entre moteurs/régions.
    std::vector<float> output(frames * static_cast<std::size_t>(channels), 0.0f);
    for (int c = 0; c < channels; ++c) {
        const std::size_t available = rendered[c].size();
        if (available <= startDelay) continue;
        const std::size_t copyFrames = std::min(frames, available - startDelay);
        for (std::size_t i = 0; i < copyFrames; ++i) {
            output[i * static_cast<std::size_t>(channels) + c] =
                rendered[c][startDelay + i];
        }
    }

    return output;
}

std::vector<float> processInterleaved(
    const float* input,
    std::size_t frames,
    int channels,
    int sampleRate,
    double pitchScale,
    std::string* error) {

    if (!validScale(pitchScale)) {
        if (error) *error = "invalid pitch scale";
        return {};
    }
    const double one = pitchScale;
    return processInterleavedCurve(
        input, frames, channels, sampleRate,
        &one, 1, std::max<std::size_t>(1, frames), error);
}

} // namespace rhaygold
