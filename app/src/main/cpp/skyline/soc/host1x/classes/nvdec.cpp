// SPDX-License-Identifier: MPL-2.0
// Copyright © 2021 Skyline Team and Contributors (https://github.com/skyline-emu/)
// Copyright © 2023 Strato Team and Contributors (https://github.com/strato-emu/)

#include <soc.h>
#include "nvdec.h"

namespace skyline::soc::host1x {
    namespace {
        constexpr u32 SetCodecIdMethodId{0x80};
        constexpr u32 ExecuteMethodId{0xC0};
    }

    NvDecDevice::NvDecDevice(const DeviceState &state) : state{state}, codec{registers} {}

    void NvDecDevice::CallMethod(u32 method, u32 argument) {
        std::scoped_lock lock{mutex};

        if (method >= vdec::NvdecRegisters::RegisterCount) {
            LOGW("Out of bounds NVDEC method called: 0x{:X} argument: 0x{:X}", method, argument);
            return;
        }

        // Register values (mostly IOVAs) are passed shifted right by 8, unshift them on write
        registers.regArray[method] = static_cast<u64>(argument) << 8;

        switch (method) {
            case SetCodecIdMethodId:
                codec.SetTargetCodec(static_cast<vdec::VideoCodec>(argument));
                break;
            case ExecuteMethodId:
                Execute();
                break;
            default:
                break;
        }
    }

    void NvDecDevice::Execute() {
        codec.Decode(state.soc->smmu);
    }

    std::unique_ptr<vdec::Frame> NvDecDevice::GetFrame() {
        std::scoped_lock lock{mutex};
        return codec.GetCurrentFrame();
    }

    NvDecClass::NvDecClass(std::function<void()> opDoneCallback, NvDecDevice &device)
        : opDoneCallback{std::move(opDoneCallback)}, device{device} {}

    void NvDecClass::CallMethod(u32 method, u32 argument) {
        device.CallMethod(method, argument);

        // Decoding is fully synchronous so the operation is done as soon as the method returns
        if (method == ExecuteMethodId)
            opDoneCallback();
    }
}
