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


package org.gearvrf;

import android.app.Activity;
import android.opengl.GLSurfaceView;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingQueue;

import org.gearvrf.asynchronous.GVRAsynchronousResourceLoader;

/*
 * This is the most important part of gvrf.
 * Initialization can be told as 2 parts. A General part and the GL part.
 * The general part needs nothing special but the GL part needs a GL context.
 * Since something being done while the GL context creates a surface is time-efficient,
 * the general initialization is done in the constructor and the GL initialization is
 * done in onSurfaceCreated().
 * 
 * After the initialization, gvrf works with 2 types of threads.
 * Input threads, and a GL thread.
 * Input threads are about the sensor, joysticks, and keyboards. The send data to gvrf.
 * gvr handles those data as a message. It saves the data, doesn't do something
 * immediately. That's because gvrf is built to do everything about the scene in the GL thread.
 * There might be some pros by doing some rendering related stuffs outside the GL thread,
 * but since I thought simplicity of the structure results in efficiency, I didn't do that.
 * 
 * Now it's about the GL thread. It lets the user handle the scene by calling the users GVRScript.onStep().
 * There are also GVRFrameListeners, GVRAnimationEngine, and Runnables but they aren't that special.
 * Then the methods of GVRRenderer is called to render the main scene.
 */

/**
 * This is the core internal class.
 * 
 * It implements {@link GVRContext}. It handles Android application callbacks
 * like cycles such as the standard Android {@link Activity#onResume()},
 * {@link Activity#onPause()}, and {@link Activity#onDestroy()}; and the
 * standard {@link GLSurfaceView#Renderer} callbacks.
 * 
 * <p>
 * Most importantly, {@link #onDrawFrame()} does the actual rendering, using the
 * current orientation from
 * {@link #onRotationSensor(long, float, float, float, float, float, float, float)
 * onRotationSensor()} to draw the scene graph properly.
 */
class GVRViewManager extends GVRContext implements RotationSensorListener {

    private static final String TAG = "GVRViewManager";

    private final Queue<Runnable> mRunnables = new LinkedBlockingQueue<Runnable>();
    private final List<GVRDrawFrameListener> mFrameListeners = new ArrayList<GVRDrawFrameListener>();

    private final GVRScript mScript;
    private final RotationSensor mRotationSensor;

    private final GVRLensInfo mLensInfo;
    private GVRRenderBundle mRenderBundle = null;
    private GVRScene mMainScene = null;
    private GVRScene mSensoredScene = null;

    private long mPreviousTimeNanos = 0l;
    private float mFrameTime = 0.0f;
    private final List<Integer> mDownKeys = new ArrayList<Integer>();

    private final GVRReferenceQueue mReferenceQueue = new GVRReferenceQueue();
    private final GVRRecyclableObjectProtector mRecyclableObjectProtector = new GVRRecyclableObjectProtector();
    GVRActivity mActivity;
    private int mCurrentEye;
    private native void renderCamera(long appPtr,
            long scene, long camera,
            long renderTexture, long shaderManager,
            long postEffectShaderManager, long postEffectRenderTextureA,
            long postEffectRenderTextureB, long[] extraPostEffectData);

    /**
     * Constructs GVRViewManager object with GVRScript which controls GL
     * activities
     * 
     * @param gvrActivity
     *            Current activity object
     * @param gvrScript
     *            {@link GVRScript} which describes
     * @param distortionDataFileName
     *            distortion filename under assets folder
     * @param renderer
     *            Renders the scene described by {@code gvrScript} into a
     *            {@link SurfaceView}.
     */
    GVRViewManager(GVRActivity gvrActivity, GVRScript gvrScript,
            String distortionDataFileName, GVRSurfaceViewRenderer renderer) {
        super(gvrActivity);

        GVRAsynchronousResourceLoader.setup(this);

        /*
         * Links with the script.
         */
        mScript = gvrScript;
        mActivity = gvrActivity;

        /*
         * Starts listening to the sensor.
         */
        mRotationSensor = new RotationSensor(gvrActivity, this);

        /*
         * Sets things with the numbers in the xml.
         */
        GVRXMLParser xmlParser = new GVRXMLParser(gvrActivity.getAssets(),
                distortionDataFileName);

        DisplayMetrics metrics = new DisplayMetrics();
        gvrActivity.getWindowManager().getDefaultDisplay().getMetrics(metrics);

        final float INCH_TO_METERS = 0.0254f;
        int screenWidthPixels = metrics.widthPixels;
        int screenHeightPixels = metrics.heightPixels;
        float screenWidthMeters = (float) screenWidthPixels / metrics.xdpi
                * INCH_TO_METERS;
        float screenHeightMeters = (float) screenHeightPixels / metrics.ydpi
                * INCH_TO_METERS;

        mLensInfo = new GVRLensInfo(screenWidthPixels,
                screenHeightPixels, screenWidthMeters, screenHeightMeters,
                xmlParser);

        GVRPerspectiveCamera.setDefaultFovY(xmlParser.getFovY());
// Different width/height aspect ratio makes the rendered screen warped when the screen rotates
//        GVRPerspectiveCamera.setDefaultAspectRatio(mLensInfo
//                .getRealScreenWidthMeters()
//                / mLensInfo.getRealScreenHeightMeters());
    }

    /**
     * Called when the system is about to start resuming a previous activity.
     * This is typically used to commit unsaved changes to persistent data, stop
     * animations and other things that may be consuming CPU, etc.
     * Implementations of this method must be very quick because the next
     * activity will not be resumed until this method returns.
     */
    void onPause() {
        Log.v(TAG, "onPause");
        mRotationSensor.onPause();
    }

    /**
     * Called when the activity will start interacting with the user. At this
     * point your activity is at the top of the activity stack, with user input
     * going to it.
     */
    void onResume() {
        Log.v(TAG, "onResume");
        mRotationSensor.onResume();
    }

    /**
     * The final call you receive before your activity is destroyed.
     */
    void onDestroy() {
        Log.v(TAG, "onDestroy");
        mReferenceQueue.onDestroy();
        mRotationSensor.onDestroy();
    }

    /**
     * Called when the surface changed size. When
     * setPreserveEGLContextOnPause(true) is called in the surface, this is
     * called only once.
     */
    void onSurfaceCreated() {
        Log.v(TAG, "onSurfaceCreated");

        // Reduce contention with other Android processes
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);

        mPreviousTimeNanos = GVRTime.getCurrentTime();

        /*
         * GL Initializations.
         */
        mRenderBundle = new GVRRenderBundle(this, mLensInfo);
        mMainScene = new GVRScene(this);
        mScript.onInit(this);

        Log.v(TAG, "onSurfaceCreated end");
    }

    private void renderCamera(long activity_ptr,
            GVRScene scene, GVRCamera camera,
            GVRRenderTexture renderTexture, GVRRenderBundle renderBundle,
            Vector<GVRPostEffect> extraPostEffectDataVector) {
        long[] extraPostEffectData = new long[extraPostEffectDataVector.size()];
        for (int i = 0; i < extraPostEffectDataVector.size(); ++i) {
            extraPostEffectData[i] = extraPostEffectDataVector.get(i).getPtr();
        }    
        renderCamera(activity_ptr, 
                scene.getPtr(),
                camera.getPtr(),
                renderTexture.getPtr(),
                renderBundle.getMaterialShaderManager().getPtr(),
                renderBundle.getPostEffectShaderManager().getPtr(),
                renderBundle.getPostEffectRenderTextureA().getPtr(),
                renderBundle.getPostEffectRenderTextureB().getPtr(),
                extraPostEffectData);
    }

    /**
     * Called when the surface is created or recreated. Avoided because this can
     * be called twice at the beginning.
     */
    void onSurfaceChanged(int width, int height) {
        Log.v(TAG, "onSurfaceChanged");
    }

    void onDrawEyeView(int eye, float fovDegrees) {
        mCurrentEye = eye;
        if (!(mSensoredScene == null || !mMainScene.equals(mSensoredScene))) {
            Vector<GVRPostEffect> postEffectData = new
                    Vector<GVRPostEffect>();
            if (eye == 1) {
                mMainScene.getMainCameraRig().predict(4.0f / 60.0f);
                renderCamera(mActivity.appPtr,
                        mMainScene,
                        mMainScene.getMainCameraRig().getRightCamera(),
                        mRenderBundle.getRightRenderTexture(),
                        mRenderBundle,
                        postEffectData);
                mActivity.setCamera(mMainScene.getMainCameraRig().getRightCamera());
            } else {
                mReferenceQueue.clean();
                mRecyclableObjectProtector.clean();
                mFrameTime = (GVRTime.getCurrentTime() - mPreviousTimeNanos) / 1e9f;
                mPreviousTimeNanos = GVRTime.getCurrentTime();
                // call onDrawFrame when there must be eye = 0, otherwise animation gets slow
            Runnable runnable = null;
            while ((runnable = mRunnables.poll()) != null) {
                runnable.run();
            }
                synchronized (mFrameListeners) {
                    for (GVRDrawFrameListener listener : mFrameListeners) {
                        listener.onDrawFrame(mFrameTime);
                    }
                }
                mScript.onStep();
                mMainScene.getMainCameraRig().predict(3.5f / 60.0f);
                renderCamera(mActivity.appPtr,
                        mMainScene,
                        mMainScene.getMainCameraRig().getLeftCamera(),
                        mRenderBundle.getLeftRenderTexture(),
                        mRenderBundle,
                        postEffectData);
                mActivity.setCamera(mMainScene.getMainCameraRig().getLeftCamera());
            }
        }
    }
    
    /**
     * Called to draw the current frames in the view.
     */
    void onDrawFrame() {
        if (mCurrentEye == 1) {
            mActivity.setCamera(mMainScene.getMainCameraRig().getLeftCamera());
        } else {
            mActivity.setCamera(mMainScene.getMainCameraRig().getRightCamera());
        }
    }

    /**
     * Called to reset current sensor data.
     * 
     * @param timeStamp current time stamp
     * @param rotationW Quaternion rotation W
     * @param rotationX Quaternion rotation X
     * @param rotationY Quaternion rotation Y
     * @param rotationZ Quaternion rotation Z
     * @param gyroX Gyro rotation X
     * @param gyroY Gyro rotation Y
     * @param gyroZ Gyro rotation Z
     */
    @Override
    public void onRotationSensor(long timeStamp, float rotationW,
            float rotationX, float rotationY, float rotationZ, float gyroX,
            float gyroY, float gyroZ) {
        GVRCameraRig cameraRig = null;
        if (mMainScene != null) {
            cameraRig = mMainScene.getMainCameraRig();
        }

        if (cameraRig != null) {
            cameraRig.setRotationSensorData(timeStamp, rotationW, rotationX,
                    rotationY, rotationZ, gyroX, gyroY, gyroZ);

            if (mSensoredScene == null || !mMainScene.equals(mSensoredScene)) {
                cameraRig.resetYaw();
                mSensoredScene = mMainScene;
            }
        }
    }

    /**
     * Called when a key was pressed down and not handled by any of the views
     * inside of the activity.
     * 
     * @param keyCode
     *            The value in {@link event#getKeyCode() event.getKeyCode()}
     * @param event
     *            Description of the key event
     */
    void onKeyDown(int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            synchronized (mDownKeys) {
                mDownKeys.add(keyCode);
            }
        }
    }

    @Override
    public GVRScene getMainScene() {
        return mMainScene;
    }

    @Override
    public void setMainScene(GVRScene scene) {
        mMainScene = scene;
    }

    @Override
    public boolean isKeyDown(int keyCode) {
        synchronized (mDownKeys) {
            return mDownKeys.contains(keyCode);
        }
    }

    @Override
    public float getFrameTime() {
        return mFrameTime;
    }

    @Override
    public void runOnGlThread(Runnable runnable) {
        mRunnables.add(runnable);
    }

    @Override
    public void registerDrawFrameListener(GVRDrawFrameListener frameListener) {
        synchronized (mFrameListeners) {
            mFrameListeners.add(frameListener);
        }
    }

    @Override
    public void unregisterDrawFrameListener(GVRDrawFrameListener frameListener) {
        synchronized (mFrameListeners) {
            mFrameListeners.remove(frameListener);
        }
    }

    @Override
    GVRReferenceQueue getReferenceQueue() {
        return mReferenceQueue;
    }

    @Override
    GVRRenderBundle getRenderBundle() {
        return mRenderBundle;
    }

    @Override
    GVRRecyclableObjectProtector getRecyclableObjectProtector() {
        return mRecyclableObjectProtector;
    }
}
