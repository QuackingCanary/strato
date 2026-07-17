// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright © 2020 yuzu Emulator Project (https://yuzu-emu.org/)
// Copyright © 2023 Strato Team and Contributors (https://github.com/strato-emu/)

#pragma once

#include <array>
#include <vector>
#include <common.h>

namespace skyline::soc::host1x::vdec {
    struct Vp9FrameDimensions {
        i16 width;
        i16 height;
        i16 lumaPitch;
        i16 chromaPitch;
    };
    static_assert(sizeof(Vp9FrameDimensions) == 0x8, "Vp9FrameDimensions is an invalid size");

    namespace frame_flags {
        constexpr u32 IsKeyFrame{1U << 0};
        constexpr u32 LastFrameIsKeyFrame{1U << 1};
        constexpr u32 FrameSizeChanged{1U << 2};
        constexpr u32 ErrorResilientMode{1U << 3};
        constexpr u32 LastShowFrame{1U << 4};
        constexpr u32 IntraOnly{1U << 5};
    }

    struct Segmentation {
        u8 enabled;
        u8 updateMap;
        u8 temporalUpdate;
        u8 absDelta;
        std::array<u32, 8> featureMask;
        std::array<std::array<i16, 4>, 8> featureData;
    };
    static_assert(sizeof(Segmentation) == 0x64, "Segmentation is an invalid size");

    struct LoopFilter {
        u8 modeRefDeltaEnabled;
        std::array<i8, 4> refDeltas;
        std::array<i8, 2> modeDeltas;
    };
    static_assert(sizeof(LoopFilter) == 0x7, "LoopFilter is an invalid size");

    struct Vp9EntropyProbs {
        std::array<u8, 36> yModeProb;              // 0x0000
        std::array<u8, 64> partitionProb;          // 0x0024
        std::array<u8, 1728> coefProbs;            // 0x0064
        std::array<u8, 8> switchableInterpProb;    // 0x0724
        std::array<u8, 28> interModeProb;          // 0x072C
        std::array<u8, 4> intraInterProb;          // 0x0748
        std::array<u8, 5> compInterProb;           // 0x074C
        std::array<u8, 10> singleRefProb;          // 0x0751
        std::array<u8, 5> compRefProb;             // 0x075B
        std::array<u8, 6> tx32x32Prob;             // 0x0760
        std::array<u8, 4> tx16x16Prob;             // 0x0766
        std::array<u8, 2> tx8x8Prob;               // 0x076A
        std::array<u8, 3> skipProbs;               // 0x076C
        std::array<u8, 3> joints;                  // 0x076F
        std::array<u8, 2> sign;                    // 0x0772
        std::array<u8, 20> classes;                // 0x0774
        std::array<u8, 2> class0;                  // 0x0788
        std::array<u8, 20> probBits;               // 0x078A
        std::array<u8, 12> class0Fr;               // 0x079E
        std::array<u8, 6> fr;                      // 0x07AA
        std::array<u8, 2> class0Hp;                // 0x07B0
        std::array<u8, 2> highPrecision;           // 0x07B2
    };
    static_assert(sizeof(Vp9EntropyProbs) == 0x7B4, "Vp9EntropyProbs is an invalid size");

    struct Vp9PictureInfo {
        u32 bitstreamSize;
        std::array<u64, 4> frameOffsets;
        std::array<i8, 4> refFrameSignBias;
        i32 baseQIndex;
        i32 yDcDeltaQ;
        i32 uvDcDeltaQ;
        i32 uvAcDeltaQ;
        i32 transformMode;
        i32 interpFilter;
        i32 referenceMode;
        i32 log2TileCols;
        i32 log2TileRows;
        std::array<i8, 4> refDeltas;
        std::array<i8, 2> modeDeltas;
        Vp9EntropyProbs entropy;
        Vp9FrameDimensions frameSize;
        u8 firstLevel;
        u8 sharpnessLevel;
        bool isKeyFrame;
        bool intraOnly;
        bool lastFrameWasKey;
        bool errorResilientMode;
        bool lastFrameShown;
        bool showFrame;
        bool lossless;
        bool allowHighPrecisionMv;
        bool segmentEnabled;
        bool modeRefDeltaEnabled;
    };

    struct Vp9FrameContainer {
        Vp9PictureInfo info{};
        std::vector<u8> bitStream;
    };

    /**
     * @brief The VP9 picture info structure as filled into guest memory by the driver for NVDEC
     */
    struct Vp9PictureInfoStruct {
        u32 _pad0_[12];                        // 0x00
        u32 bitstreamSize;                     // 0x30
        u32 _pad1_[5];                         // 0x34
        Vp9FrameDimensions lastFrameSize;      // 0x48
        Vp9FrameDimensions goldenFrameSize;    // 0x50
        Vp9FrameDimensions altFrameSize;       // 0x58
        Vp9FrameDimensions currentFrameSize;   // 0x60
        u32 vp9Flags;                          // 0x68
        std::array<i8, 4> refFrameSignBias;    // 0x6C
        u8 firstLevel;                         // 0x70
        u8 sharpnessLevel;                     // 0x71
        u8 baseQIndex;                         // 0x72
        u8 yDcDeltaQ;                          // 0x73
        u8 uvAcDeltaQ;                         // 0x74
        u8 uvDcDeltaQ;                         // 0x75
        u8 lossless;                           // 0x76
        u8 txMode;                             // 0x77
        u8 allowHighPrecisionMv;               // 0x78
        u8 interpFilter;                       // 0x79
        u8 referenceMode;                      // 0x7A
        u8 _pad2_[3];                          // 0x7B
        u8 log2TileCols;                       // 0x7E
        u8 log2TileRows;                       // 0x7F
        Segmentation segmentation;             // 0x80
        LoopFilter loopFilter;                 // 0xE4
        u8 _pad3_[21];                         // 0xEB

        Vp9PictureInfo Convert() const {
            return {
                .bitstreamSize = bitstreamSize,
                .frameOffsets{},
                .refFrameSignBias = refFrameSignBias,
                .baseQIndex = baseQIndex,
                .yDcDeltaQ = yDcDeltaQ,
                .uvDcDeltaQ = uvDcDeltaQ,
                .uvAcDeltaQ = uvAcDeltaQ,
                .transformMode = txMode,
                .interpFilter = interpFilter,
                .referenceMode = referenceMode,
                .log2TileCols = log2TileCols,
                .log2TileRows = log2TileRows,
                .refDeltas = loopFilter.refDeltas,
                .modeDeltas = loopFilter.modeDeltas,
                .entropy{},
                .frameSize = currentFrameSize,
                .firstLevel = firstLevel,
                .sharpnessLevel = sharpnessLevel,
                .isKeyFrame = (vp9Flags & frame_flags::IsKeyFrame) != 0,
                .intraOnly = (vp9Flags & frame_flags::IntraOnly) != 0,
                .lastFrameWasKey = (vp9Flags & frame_flags::LastFrameIsKeyFrame) != 0,
                .errorResilientMode = (vp9Flags & frame_flags::ErrorResilientMode) != 0,
                .lastFrameShown = (vp9Flags & frame_flags::LastShowFrame) != 0,
                .showFrame = true,
                .lossless = lossless != 0,
                .allowHighPrecisionMv = allowHighPrecisionMv != 0,
                .segmentEnabled = segmentation.enabled != 0,
                .modeRefDeltaEnabled = loopFilter.modeRefDeltaEnabled != 0,
            };
        }
    };
    static_assert(sizeof(Vp9PictureInfoStruct) == 0x100, "Vp9PictureInfoStruct is an invalid size");

    /**
     * @brief The VP9 entropy probability structure layout as written by the driver for NVDEC
     */
    struct Vp9EntropyProbsStruct {
        u8 _pad0_[1024];                                  // 0x0000
        std::array<u8, 28> interModeProb;                 // 0x0400
        std::array<u8, 4> intraInterProb;                 // 0x041C
        u8 _pad1_[80];                                    // 0x0420
        std::array<u8, 2> tx8x8Prob;                      // 0x0470
        std::array<u8, 4> tx16x16Prob;                    // 0x0472
        std::array<u8, 6> tx32x32Prob;                    // 0x0476
        std::array<u8, 4> yModeProbE8;                    // 0x047C
        std::array<std::array<u8, 8>, 4> yModeProbE0E7;   // 0x0480
        u8 _pad2_[64];                                    // 0x04A0
        std::array<u8, 64> partitionProb;                 // 0x04E0
        u8 _pad3_[10];                                    // 0x0520
        std::array<u8, 8> switchableInterpProb;           // 0x052A
        std::array<u8, 5> compInterProb;                  // 0x0532
        std::array<u8, 3> skipProbs;                      // 0x0537
        u8 _pad4_;                                        // 0x053A
        std::array<u8, 3> joints;                         // 0x053B
        std::array<u8, 2> sign;                           // 0x053E
        std::array<u8, 2> class0;                         // 0x0540
        std::array<u8, 6> fr;                             // 0x0542
        std::array<u8, 2> class0Hp;                       // 0x0548
        std::array<u8, 2> highPrecision;                  // 0x054A
        std::array<u8, 20> classes;                       // 0x054C
        std::array<u8, 12> class0Fr;                      // 0x0560
        std::array<u8, 20> predBits;                      // 0x056C
        std::array<u8, 10> singleRefProb;                 // 0x0580
        std::array<u8, 5> compRefProb;                    // 0x058A
        u8 _pad5_[17];                                    // 0x058F
        std::array<u8, 2304> coefProbs;                   // 0x05A0

        void Convert(Vp9EntropyProbs &fc) const {
            fc.interModeProb = interModeProb;
            fc.intraInterProb = intraInterProb;
            fc.tx8x8Prob = tx8x8Prob;
            fc.tx16x16Prob = tx16x16Prob;
            fc.tx32x32Prob = tx32x32Prob;

            for (size_t i{}; i < 4; i++)
                for (size_t j{}; j < 9; j++)
                    fc.yModeProb[j + 9 * i] = j < 8 ? yModeProbE0E7[i][j] : yModeProbE8[i];

            fc.partitionProb = partitionProb;
            fc.switchableInterpProb = switchableInterpProb;
            fc.compInterProb = compInterProb;
            fc.skipProbs = skipProbs;
            fc.joints = joints;
            fc.sign = sign;
            fc.class0 = class0;
            fc.fr = fr;
            fc.class0Hp = class0Hp;
            fc.highPrecision = highPrecision;
            fc.classes = classes;
            fc.class0Fr = class0Fr;
            fc.probBits = predBits;
            fc.singleRefProb = singleRefProb;
            fc.compRefProb = compRefProb;

            // Skip the 4th element as it goes unused
            for (size_t i{}; i < coefProbs.size(); i += 4) {
                const size_t j{i - i / 4};
                fc.coefProbs[j] = coefProbs[i];
                fc.coefProbs[j + 1] = coefProbs[i + 1];
                fc.coefProbs[j + 2] = coefProbs[i + 2];
            }
        }
    };
    static_assert(sizeof(Vp9EntropyProbsStruct) == 0xEA0, "Vp9EntropyProbsStruct is an invalid size");

    #define ASSERT_POSITION(type, field, position) static_assert(offsetof(type, field) == position, "Field " #field " has an invalid position")
    ASSERT_POSITION(Vp9EntropyProbs, partitionProb, 0x0024);
    ASSERT_POSITION(Vp9EntropyProbs, switchableInterpProb, 0x0724);
    ASSERT_POSITION(Vp9EntropyProbs, sign, 0x0772);
    ASSERT_POSITION(Vp9EntropyProbs, class0Fr, 0x079E);
    ASSERT_POSITION(Vp9EntropyProbs, highPrecision, 0x07B2);
    ASSERT_POSITION(Vp9PictureInfoStruct, bitstreamSize, 0x30);
    ASSERT_POSITION(Vp9PictureInfoStruct, lastFrameSize, 0x48);
    ASSERT_POSITION(Vp9PictureInfoStruct, firstLevel, 0x70);
    ASSERT_POSITION(Vp9PictureInfoStruct, segmentation, 0x80);
    ASSERT_POSITION(Vp9PictureInfoStruct, loopFilter, 0xE4);
    ASSERT_POSITION(Vp9EntropyProbsStruct, interModeProb, 0x400);
    ASSERT_POSITION(Vp9EntropyProbsStruct, tx8x8Prob, 0x470);
    ASSERT_POSITION(Vp9EntropyProbsStruct, partitionProb, 0x4E0);
    ASSERT_POSITION(Vp9EntropyProbsStruct, class0, 0x540);
    ASSERT_POSITION(Vp9EntropyProbsStruct, class0Fr, 0x560);
    ASSERT_POSITION(Vp9EntropyProbsStruct, coefProbs, 0x5A0);
    #undef ASSERT_POSITION
}
