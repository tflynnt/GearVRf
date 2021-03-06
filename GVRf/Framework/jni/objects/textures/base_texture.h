/* Copyright 2015 Samsung Electronics Co., LTD
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


/***************************************************************************
 * Texture made by a bitmap.
 ***************************************************************************/

#ifndef BASE_TEXTURE_H_
#define BASE_TEXTURE_H_

#include <string>

#include <android/bitmap.h>

#include "objects/textures/texture.h"
#include "util/gvr_log.h"

namespace gvr {
class BaseTexture: public Texture {
public:
    explicit BaseTexture(JNIEnv* env, jobject bitmap) :
            Texture(new GLTexture(TARGET)) {
        AndroidBitmapInfo info;
        void *pixels;
        int ret;
        if ((ret = AndroidBitmap_getInfo(env, bitmap, &info)) < 0) {
            std::string error = "AndroidBitmap_getInfo () failed! error = "
                    + ret;
            LOGE("%s", error.c_str());
            throw error;
        }
        if ((ret = AndroidBitmap_lockPixels(env, bitmap, &pixels)) < 0) {
            std::string error = "AndroidBitmap_lockPixels () failed! error = "
                    + ret;
            LOGE("%s", error.c_str());
            throw error;
        }
        AndroidBitmap_unlockPixels(env, bitmap);

        glBindTexture(GL_TEXTURE_2D, gl_texture_->id());
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, info.width, info.height, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    }

    explicit BaseTexture(int width, int height, const unsigned char* pixels) :
            Texture(new GLTexture(TARGET)) {
        glBindTexture(GL_TEXTURE_2D, gl_texture_->id());
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA,
                GL_UNSIGNED_BYTE, pixels);
    }

    GLenum getTarget() const {
        return TARGET;
    }

private:
    BaseTexture(const BaseTexture& base_texture);
    BaseTexture(BaseTexture&& base_texture);
    BaseTexture& operator=(const BaseTexture& base_texture);
    BaseTexture& operator=(BaseTexture&& base_texture);

private:
    static const GLenum TARGET = GL_TEXTURE_2D;
};

}
#endif
