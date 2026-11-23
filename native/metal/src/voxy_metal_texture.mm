// voxy_metal_texture.mm — MTLTexture and MTLRenderPassDescriptor.

#include "voxy_metal.h"
#include <cstdlib>
#include <cstring>

extern "C" JNIEXPORT jlong JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlNewTextureDescriptor(
        JNIEnv *, jclass,
        jint textureType, jint pixelFormat,
        jint width, jint height,
        jint mipmapLevels, jint usage, jint storageMode) {
    @autoreleasepool {
        MTLTextureDescriptor *desc = [[MTLTextureDescriptor alloc] init];
        desc.textureType = (MTLTextureType)textureType;
        desc.pixelFormat = (MTLPixelFormat)pixelFormat;
        desc.width = (NSUInteger)width;
        desc.height = (NSUInteger)height;
        desc.depth = 1;
        desc.mipmapLevelCount = (NSUInteger)mipmapLevels;
        desc.arrayLength = 1;
        desc.sampleCount = 1;
        desc.usage = (MTLTextureUsage)usage;
        desc.storageMode = (MTLStorageMode)storageMode;
        return voxy_handle_from(desc);
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlDeviceNewTexture(
        JNIEnv *, jclass, jlong deviceHandle, jlong descriptorHandle) {
    @autoreleasepool {
        if (deviceHandle == 0 || descriptorHandle == 0) return 0;
        id<MTLDevice> device = voxy_handle_cast<id<MTLDevice>>(deviceHandle);
        MTLTextureDescriptor *desc = voxy_handle_cast<MTLTextureDescriptor *>(descriptorHandle);
        id<MTLTexture> tex = [device newTextureWithDescriptor:desc];
        if (tex == nil) return 0;
        return voxy_handle_from(tex);
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlTextureNewView(
        JNIEnv *, jclass, jlong textureHandle, jint pixelFormat) {
    @autoreleasepool {
        if (textureHandle == 0) return 0;
        id<MTLTexture> tex = voxy_handle_cast<id<MTLTexture>>(textureHandle);
        id<MTLTexture> view = [tex newTextureViewWithPixelFormat:(MTLPixelFormat)pixelFormat];
        if (view == nil) return 0;
        return voxy_handle_from(view);
    }
}

// M13 chunk 3 support: per-mip / per-slice texture view. Needed by
// HiZBuffer.buildMipChain on Metal — each pyramid level needs to be
// independently bindable as both a sampling source (level i-1) and as
// the depth-attachment target (level i), without the GL_TEXTURE_BASE_LEVEL
// / GL_TEXTURE_MAX_LEVEL global-state hack the GL path uses.
//
// textureType matches the parent's; pass MTLTextureType2D for a
// single-slice 2D view. levelCount / sliceCount of 0 are clamped to 1 so
// callers can pass 0 to mean "default range" without an Objective-C-side
// arithmetic surprise.
extern "C" JNIEXPORT jlong JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlTextureNewSubresourceView(
        JNIEnv *, jclass, jlong textureHandle, jint pixelFormat, jint textureType,
        jint baseLevel, jint levelCount, jint baseSlice, jint sliceCount) {
    @autoreleasepool {
        if (textureHandle == 0) return 0;
        id<MTLTexture> tex = voxy_handle_cast<id<MTLTexture>>(textureHandle);
        NSUInteger lvls = levelCount > 0 ? (NSUInteger)levelCount : 1;
        NSUInteger slcs = sliceCount > 0 ? (NSUInteger)sliceCount : 1;
        id<MTLTexture> view = [tex newTextureViewWithPixelFormat:(MTLPixelFormat)pixelFormat
                                                     textureType:(MTLTextureType)textureType
                                                          levels:NSMakeRange((NSUInteger)baseLevel, lvls)
                                                          slices:NSMakeRange((NSUInteger)baseSlice, slcs)];
        if (view == nil) return 0;
        return voxy_handle_from(view);
    }
}

// M13 chunk 1: CPU readback from a Shared/Managed-storage MTLTexture. The
// Metal-native bakery uses this to pull its rendered bake target into the
// persistent-mapped download stream the rest of the bakery system expects.
// Caller must have synchronised on the texture (e.g. via
// commandBufferWaitUntilCompleted) before invoking — getBytes itself doesn't
// wait for GPU writes.
//
// Mirrors mtlTextureReplaceRegion but in the read direction; uses the same
// 2D region helper. Private-storage textures will return garbage / crash on
// some macOS versions, so the Java wrapper guards on storageMode.
extern "C" JNIEXPORT void JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlTextureGetBytes(
        JNIEnv *, jclass, jlong textureHandle, jint level,
        jint x, jint y, jint width, jint height,
        jlong dataAddr, jint bytesPerRow) {
    @autoreleasepool {
        if (textureHandle == 0 || dataAddr == 0) return;
        id<MTLTexture> tex = voxy_handle_cast<id<MTLTexture>>(textureHandle);
        MTLRegion region = MTLRegionMake2D((NSUInteger)x, (NSUInteger)y,
                                            (NSUInteger)width, (NSUInteger)height);
        [tex getBytes:(void *)dataAddr
          bytesPerRow:(NSUInteger)bytesPerRow
           fromRegion:region
          mipmapLevel:(NSUInteger)level];
    }
}

extern "C" JNIEXPORT void JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlTextureReplaceRegion(
        JNIEnv *, jclass, jlong textureHandle, jint level,
        jint x, jint y, jint width, jint height,
        jlong dataAddr, jint bytesPerRow) {
    @autoreleasepool {
        if (textureHandle == 0 || dataAddr == 0) return;
        id<MTLTexture> tex = voxy_handle_cast<id<MTLTexture>>(textureHandle);
        MTLRegion region = MTLRegionMake2D((NSUInteger)x, (NSUInteger)y,
                                            (NSUInteger)width, (NSUInteger)height);
        [tex replaceRegion:region
               mipmapLevel:(NSUInteger)level
                 withBytes:(const void *)dataAddr
               bytesPerRow:(NSUInteger)bytesPerRow];
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlTextureGetWidth(
        JNIEnv *, jclass, jlong handle) {
    @autoreleasepool {
        if (handle == 0) return 0;
        return (jint)[voxy_handle_cast<id<MTLTexture>>(handle) width];
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlTextureGetHeight(
        JNIEnv *, jclass, jlong handle) {
    @autoreleasepool {
        if (handle == 0) return 0;
        return (jint)[voxy_handle_cast<id<MTLTexture>>(handle) height];
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlTextureGetPixelFormat(
        JNIEnv *, jclass, jlong handle) {
    @autoreleasepool {
        if (handle == 0) return 0;
        return (jint)[voxy_handle_cast<id<MTLTexture>>(handle) pixelFormat];
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlTextureGetMipmapLevelCount(
        JNIEnv *, jclass, jlong handle) {
    @autoreleasepool {
        if (handle == 0) return 0;
        return (jint)[voxy_handle_cast<id<MTLTexture>>(handle) mipmapLevelCount];
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlTextureGetTextureType(
        JNIEnv *, jclass, jlong handle) {
    @autoreleasepool {
        if (handle == 0) return 0;
        return (jint)[voxy_handle_cast<id<MTLTexture>>(handle) textureType];
    }
}

// -------- Render pass descriptor --------

extern "C" JNIEXPORT jlong JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlNewRenderPassDescriptor(
        JNIEnv *, jclass) {
    @autoreleasepool {
        MTLRenderPassDescriptor *desc = [MTLRenderPassDescriptor renderPassDescriptor];
        if (desc == nil) return 0;
        return voxy_handle_from(desc);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlRenderPassSetColorAttachment(
        JNIEnv *, jclass, jlong descHandle, jint index,
        jlong textureHandle, jint loadAction, jint storeAction, jint level) {
    @autoreleasepool {
        if (descHandle == 0) return;
        MTLRenderPassDescriptor *desc = voxy_handle_cast<MTLRenderPassDescriptor *>(descHandle);
        MTLRenderPassColorAttachmentDescriptor *att = desc.colorAttachments[(NSUInteger)index];
        att.texture = textureHandle ? voxy_handle_cast<id<MTLTexture>>(textureHandle) : nil;
        att.loadAction = (MTLLoadAction)loadAction;
        att.storeAction = (MTLStoreAction)storeAction;
        att.level = (NSUInteger)level;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlRenderPassSetDepthAttachment(
        JNIEnv *, jclass, jlong descHandle,
        jlong textureHandle, jint loadAction, jint storeAction,
        jfloat clearDepth, jint level) {
    @autoreleasepool {
        if (descHandle == 0) return;
        MTLRenderPassDescriptor *desc = voxy_handle_cast<MTLRenderPassDescriptor *>(descHandle);
        MTLRenderPassDepthAttachmentDescriptor *att = desc.depthAttachment;
        att.texture = textureHandle ? voxy_handle_cast<id<MTLTexture>>(textureHandle) : nil;
        att.loadAction = (MTLLoadAction)loadAction;
        att.storeAction = (MTLStoreAction)storeAction;
        att.clearDepth = (double)clearDepth;
        att.level = (NSUInteger)level;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlRenderPassSetStencilAttachment(
        JNIEnv *, jclass, jlong descHandle,
        jlong textureHandle, jint loadAction, jint storeAction,
        jint clearStencil, jint level) {
    @autoreleasepool {
        if (descHandle == 0) return;
        MTLRenderPassDescriptor *desc = voxy_handle_cast<MTLRenderPassDescriptor *>(descHandle);
        MTLRenderPassStencilAttachmentDescriptor *att = desc.stencilAttachment;
        att.texture = textureHandle ? voxy_handle_cast<id<MTLTexture>>(textureHandle) : nil;
        att.loadAction = (MTLLoadAction)loadAction;
        att.storeAction = (MTLStoreAction)storeAction;
        att.clearStencil = (uint32_t)clearStencil;
        att.level = (NSUInteger)level;
    }
}

// -------- Synchronization (events) --------

extern "C" JNIEXPORT jlong JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlDeviceNewEvent(
        JNIEnv *, jclass, jlong deviceHandle) {
    @autoreleasepool {
        if (deviceHandle == 0) return 0;
        id<MTLDevice> device = voxy_handle_cast<id<MTLDevice>>(deviceHandle);
        id<MTLEvent> event = [device newEvent];
        if (event == nil) return 0;
        return voxy_handle_from(event);
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlDeviceNewSharedEvent(
        JNIEnv *, jclass, jlong deviceHandle) {
    @autoreleasepool {
        if (deviceHandle == 0) return 0;
        id<MTLDevice> device = voxy_handle_cast<id<MTLDevice>>(deviceHandle);
        id<MTLSharedEvent> event = [device newSharedEvent];
        if (event == nil) return 0;
        return voxy_handle_from(event);
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlSharedEventGetSignaledValue(
        JNIEnv *, jclass, jlong eventHandle) {
    @autoreleasepool {
        if (eventHandle == 0) return 0;
        id<MTLSharedEvent> event = voxy_handle_cast<id<MTLSharedEvent>>(eventHandle);
        return (jlong)[event signaledValue];
    }
}

extern "C" JNIEXPORT void JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlSharedEventSetSignaledValue(
        JNIEnv *, jclass, jlong eventHandle, jlong value) {
    @autoreleasepool {
        if (eventHandle == 0) return;
        id<MTLSharedEvent> event = voxy_handle_cast<id<MTLSharedEvent>>(eventHandle);
        [event setSignaledValue:(uint64_t)value];
    }
}

extern "C" JNIEXPORT void JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlCommandBufferEncodeSignalEvent(
        JNIEnv *, jclass, jlong cmdBufHandle, jlong eventHandle, jlong value) {
    @autoreleasepool {
        if (cmdBufHandle == 0 || eventHandle == 0) return;
        id<MTLCommandBuffer> cmdBuf = voxy_handle_cast<id<MTLCommandBuffer>>(cmdBufHandle);
        id<MTLEvent> event = voxy_handle_cast<id<MTLEvent>>(eventHandle);
        [cmdBuf encodeSignalEvent:event value:(uint64_t)value];
    }
}

extern "C" JNIEXPORT void JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlCommandBufferEncodeWaitForEvent(
        JNIEnv *, jclass, jlong cmdBufHandle, jlong eventHandle, jlong value) {
    @autoreleasepool {
        if (cmdBufHandle == 0 || eventHandle == 0) return;
        id<MTLCommandBuffer> cmdBuf = voxy_handle_cast<id<MTLCommandBuffer>>(cmdBufHandle);
        id<MTLEvent> event = voxy_handle_cast<id<MTLEvent>>(eventHandle);
        [cmdBuf encodeWaitForEvent:event value:(uint64_t)value];
    }
}

// -------- Shader library / pipeline --------

extern "C" JNIEXPORT jlong JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlDeviceNewLibraryWithSource(
        JNIEnv *env, jclass, jlong deviceHandle, jstring source) {
    @autoreleasepool {
        if (deviceHandle == 0 || source == nullptr) return 0;
        id<MTLDevice> device = voxy_handle_cast<id<MTLDevice>>(deviceHandle);
        const char *utf = env->GetStringUTFChars(source, nullptr);
        if (!utf) return 0;
        NSString *src = [NSString stringWithUTF8String:utf];
        env->ReleaseStringUTFChars(source, utf);

        // Pin MSL 3.0 (the macOS 13 floor; SPIRV-Cross emits 3.0) so every Mac compiles exactly
        // what the oldest supported OS compiles: with options:nil a macOS 26 dev box used the
        // newest language version and a 3.0-only failure would first show up on a user's Ventura
        // machine. VOXY_METAL_MSL_PIN=0 restores options:nil.
        static int pinMsl = -1;
        if (pinMsl < 0) {
            const char *e = getenv("VOXY_METAL_MSL_PIN");
            pinMsl = (e != nullptr && strcmp(e, "0") == 0) ? 0 : 1;
        }
        MTLCompileOptions *opts = nil;
        if (pinMsl) {
            opts = [MTLCompileOptions new];
            opts.languageVersion = MTLLanguageVersion3_0;
        }
        NSError *error = nil;
        id<MTLLibrary> lib = [device newLibraryWithSource:src options:opts error:&error];
        if (lib == nil) {
            voxy_set_last_error(error ? [error localizedDescription] : @"Unknown MSL compile error");
            return 0;
        }
        voxy_set_last_error(nil);
        return voxy_handle_from(lib);
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlLibraryNewFunction(
        JNIEnv *env, jclass, jlong libHandle, jstring name) {
    @autoreleasepool {
        if (libHandle == 0 || name == nullptr) return 0;
        id<MTLLibrary> lib = voxy_handle_cast<id<MTLLibrary>>(libHandle);
        const char *utf = env->GetStringUTFChars(name, nullptr);
        if (!utf) return 0;
        NSString *fnName = [NSString stringWithUTF8String:utf];
        env->ReleaseStringUTFChars(name, utf);

        id<MTLFunction> fn = [lib newFunctionWithName:fnName];
        if (fn == nil) return 0;
        return voxy_handle_from(fn);
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlDeviceNewComputePipelineState(
        JNIEnv *, jclass, jlong deviceHandle, jlong functionHandle, jint maxTotalThreads) {
    @autoreleasepool {
        if (deviceHandle == 0 || functionHandle == 0) return 0;
        id<MTLDevice> device = voxy_handle_cast<id<MTLDevice>>(deviceHandle);
        id<MTLFunction> fn = voxy_handle_cast<id<MTLFunction>>(functionHandle);
        NSError *error = nil;
        id<MTLComputePipelineState> pso = nil;
        if (maxTotalThreads > 0) {
            // Pin maxTotalThreadsPerThreadgroup to the shader-declared local size so
            // the compiler must accommodate it or fail HERE, loudly — dispatching
            // above the PSO's compiler-assigned max is silent UB with API validation
            // off (the wrong-thread-count failure shape of the 2026-05 LOD flicker).
            MTLComputePipelineDescriptor *desc = [MTLComputePipelineDescriptor new];
            desc.computeFunction = fn;
            desc.maxTotalThreadsPerThreadgroup = (NSUInteger) maxTotalThreads;
            pso = [device newComputePipelineStateWithDescriptor:desc
                                                        options:MTLPipelineOptionNone
                                                     reflection:nil
                                                          error:&error];
        } else {
            pso = [device newComputePipelineStateWithFunction:fn error:&error];
        }
        if (pso == nil) {
            voxy_set_last_error(error ? [error localizedDescription] : @"Compute PSO creation failed");
            return 0;
        }
        if (maxTotalThreads > 0 && pso.maxTotalThreadsPerThreadgroup < (NSUInteger) maxTotalThreads) {
            voxy_set_last_error([NSString stringWithFormat:
                    @"Compute PSO maxTotalThreadsPerThreadgroup=%lu < required local size %d",
                    (unsigned long) pso.maxTotalThreadsPerThreadgroup, maxTotalThreads]);
            return 0;
        }
        return voxy_handle_from(pso);
    }
}
