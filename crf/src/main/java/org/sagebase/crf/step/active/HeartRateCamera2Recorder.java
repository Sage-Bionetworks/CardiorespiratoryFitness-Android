/*
 *    Copyright 2018 Sage Bionetworks
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 */

package org.sagebase.crf.step.active;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.HandlerThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.WorkerThread;
import androidx.renderscript.RenderScript;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import com.google.android.gms.common.util.ArrayUtils;
import com.google.common.collect.Sets;

import org.sagebionetworks.researchstack.backbone.result.FileResult;
import org.sagebionetworks.researchstack.backbone.step.Step;
import org.sagebionetworks.researchstack.backbone.step.active.recorder.Recorder;
import org.sagebionetworks.researchstack.backbone.step.active.recorder.RecorderListener;
import org.sagebase.crf.step.CrfHeartRateStepLayout;
import org.sagebase.crf.step.active.camera_error.CameraState;
import org.sagebase.crf.step.active.confidence_error.ConfidenceState;
import org.sagebase.crf.step.active.pressure_error.PressureState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Set;

import rx.Observable;
import rx.Single;
import rx.Subscriber;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import rx.subscriptions.CompositeSubscription;

import static android.graphics.ImageFormat.YUV_420_888;
import static android.hardware.camera2.CaptureResult.*;
import static org.sagebase.crf.step.active.ImageUtils.toBitmap;

/**
 * Created by liujoshua on 2/19/2018.
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class HeartRateCamera2Recorder extends Recorder {
    private static final Logger LOG = LoggerFactory.getLogger(HeartRateCamera2Recorder.class);
    
    public static final String MP4_CONTENT_TYPE = "video/mp4";
    public static final long CAMERA_FRAME_DURATION_NANOS = 16_666_666L;
    public static final long CAMERA_EXPOSURE_DURATION_NANOS = 8_333_333L;
    //400 seems to sensitive enough to fix Huaweii Mate SE and Galaxy J7, without breaking Galaxy S9
    public static final int CAMERA_SENSITIVITY = 400;  //Increased from 60 to 400 -Nathaniel 12/18/18
    public static final int VIDEO_ENCODING_BIT_RATE = 500_000;
    public static final int VIDEO_FRAME_RATE = 60;
    public static final int VIDEO_ENCODER = MediaRecorder.VideoEncoder.H264;
    public static final int VIDEO_WIDTH = 192;
    public static final int VIDEO_SIZE = 144;
    
    private final CompositeSubscription subscriptions;
    private final List<Surface> allSurfaces = new ArrayList(3);
    private final List<Surface> surfacesNoMediaRecorder = new ArrayList<>(2);
    private final List<Surface> surfacesNoPreview = new ArrayList<>(2);
    private final BpmRecorder.HeartBeatJsonWriter heartBeatJsonWriter;
    private final RenderScript renderScript;
    
    
    // displays preview
    private TextureView textureView;
    // image processing
    private ImageReader imageReader;
    
    Date startTime;
    
    private Size mVideoSize;
    private MediaRecorder mediaRecorder;
    private final File mediaRecorderFile;
    private CameraCaptureSession cameraCaptureSession;
    private CameraManager manager;
    private boolean shouldRecordVideo;
    
    public HeartRateCamera2Recorder(String identifier, Step step, File outputDirectory,
                                    CrfHeartRateStepLayout stepLayout, boolean recordVideo) {
        super(identifier + "Video", step, outputDirectory);
        textureView = stepLayout.getCameraPreview();
    
        Context context = textureView.getContext();

        shouldRecordVideo = recordVideo;

        mediaRecorderFile = new File(getOutputDirectory(), uniqueFilename + ".mp4");
        subscriptions = new CompositeSubscription();

        heartBeatJsonWriter = new BpmRecorder.HeartBeatJsonWriter(stepLayout, stepLayout,
                stepLayout, stepLayout, stepLayout, stepLayout,
                identifier + "_rgb.json", step,
                outputDirectory );
        heartBeatJsonWriter.setRecorderListener(stepLayout);
        renderScript = RenderScript.create(context);
    }

    public double calculateVo2Max(Sex sex, double age) {
        return heartBeatJsonWriter.calculateVo2Max(sex, age);
    }

    @Override
    public void start(Context context) {
        startTime = new Date();
        heartBeatJsonWriter.start(context);
        manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            LOG.warn("This device doesn't support Camera2 API");
            recordingFailed(new IllegalStateException("This device doesn't support Camera2 API"));
            return;
        }
        String cameraId = selectCamera(manager, context);
    
        Observable<CameraCaptureSession> cameraCaptureSessionObservable =
                openCameraObservable(manager, cameraId)
                        .doOnUnsubscribe(() -> LOG.debug("Camera Capture unsubscribed"))
                        .flatMap(cameraDevice -> {
                            // video recording surface
                            mediaRecorder =
                                    createMediaRecorder(mVideoSize, mediaRecorderFile);
                            try {
                                mediaRecorder.prepare();
                            } catch (IOException e) {
                                return Observable.error(e);
                            }
                        
                            Surface recordingSurface = mediaRecorder.getSurface();
                            allSurfaces.add(recordingSurface);
                            surfacesNoPreview.add(recordingSurface);
                        
                            // preview surface
//                            textureView.setLayoutParams(new FrameLayout.LayoutParams(
//                                    mVideoSize.getWidth(),
//                                    mVideoSize.getHeight()
//                            ));
//
                            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP) {
                                //Galaxy Note 3 running android 5.0 fails to create capture session due to invalid preview surface size
                                // easiest solution for now is to not show the preview on android 5.0  - nathaniel 05/02/19
                                Surface previewSurface = new Surface(textureView.getSurfaceTexture());

                                allSurfaces.add(previewSurface);
                                surfacesNoMediaRecorder.add(previewSurface);
                            }

                            // heart rate processing surface
                            imageReader = ImageReader.newInstance(
                                    mVideoSize.getWidth(),
                                    mVideoSize.getHeight(),
                                    YUV_420_888,
                                    5);
                            
                            Surface processingSurface = imageReader.getSurface();
                            allSurfaces.add(processingSurface);
                            surfacesNoMediaRecorder.add(processingSurface);
                            surfacesNoPreview.add(processingSurface);
                        
                            return createCaptureSessionObservable(cameraDevice, allSurfaces)
                                    .doOnNext(session -> {
                                        mediaRecorder.start();
                                    })
                                    .doOnNext(session ->
                                            subscriptions.add(createImageReaderObservable(imageReader)
                                                    .observeOn(Schedulers.computation())
                                                    .map(this::toHeartBeatSample)
                                                    .subscribeOn(Schedulers.io())
                                                    .doOnUnsubscribe(() -> LOG.debug("ImageReader unsubscribed"))
                                                    .subscribe(
                                                            heartBeatJsonWriter::onHeartRateSampleDetected,
                                                            this::recordingFailed,
                                                            imageReader::close)))
                                    .doOnUnsubscribe(() -> LOG.debug("Capture session 0 unsubscribed"))
                                    .doOnUnsubscribe(() -> {
                                        try {
                                            mediaRecorder.release();
                                        } catch (Throwable t) {
                                            LOG.error("Couldn't release mediaRecorder", t);
                                        }
                                    });
                        });
    
        subscriptions.add(cameraCaptureSessionObservable
                .subscribe(
                        s -> {
                            cameraCaptureSession = s;
                            doRepeatingRequest(s, surfacesNoMediaRecorder);
                            setRecording(true);
                        }, t -> {
                            cameraCaptureSession = null;
                            recordingFailed(t);
                        },
                        () -> LOG.debug("CaptureSession completed")));
    }
    
    public void startVideoRecording() {
        if (cameraCaptureSession == null) {
            LOG.warn("Could not start video recording, cameraCaptureSession is null");
            return;
        }
        LOG.warn("Started video recording");
        doRepeatingRequest(cameraCaptureSession, surfacesNoPreview);
    }

    private HeartBeatUtil heartBeatUtil = new HeartBeatUtil();

    @WorkerThread
    public HeartBeatSample toHeartBeatSample(ImageReader imageReader) {
        Image image = imageReader.acquireNextImage();
        Bitmap bitmap =
                ImageUtils.toBitmap(renderScript, image, mVideoSize.getWidth(),
                        mVideoSize.getHeight());

        double timestamp = image.getTimestamp() * 1e-09;
        HeartBeatSample sample = heartBeatUtil.getHeartBeatSample(timestamp, bitmap);


        if (CameraState.containsIssue(image.getTimestamp(), bitmap)) {
            System.out.println("Handle the camera error");
        }
        if (ConfidenceState.containsIssue(image.getTimestamp(), bitmap)) {
            System.out.println("Low confidence");
            if (PressureState.containsIssue(image.getTimestamp(), bitmap)) {
                System.out.println("Pressure issue");
            }
        }

        image.close();
        return sample;
    }
    @Override
    public void setRecorderListener(RecorderListener listener) {
        super.setRecorderListener(listener);
        heartBeatJsonWriter.setRecorderListener(listener);
    }
    
    @Override
    public void stop() {
        heartBeatJsonWriter.stop();
        subscriptions.unsubscribe();

        if (!shouldRecordVideo && mediaRecorderFile.exists()) {
            mediaRecorderFile.delete();
        }

        //Check that the video file exists and has data before adding to results.
        if (shouldRecordVideo && mediaRecorderFile.exists()) {
            FileResult fileResult = new FileResult(fileResultIdentifier(), mediaRecorderFile, MP4_CONTENT_TYPE);
            fileResult.setStartDate(startTime);
            fileResult.setEndDate(new Date());
    
            getRecorderListener().onComplete(this, fileResult);
        }
    }

    @Override
    public void cancel() {
        heartBeatJsonWriter.cancel();
        subscriptions.unsubscribe();
        
        if (mediaRecorderFile.exists()) {
            mediaRecorderFile.delete();
        }
    }

    @SuppressLint("MissingPermission")
    @Nullable
    private String selectCamera(CameraManager manager, Context context) {
        Single.just(manager)
        .flatMapObservable(m -> {
            try {
                return Observable.just(m.getCameraIdList());
            } catch (CameraAccessException e) {
                return Observable.error(e);
            }
        });
        try {
            for (String cameraId : manager.getCameraIdList()) {

                CameraCharacteristics cameraCharacteristics
                        = manager.getCameraCharacteristics(cameraId);

                if (!cameraCharacteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)) {
                    continue;
                }

                if (CameraCharacteristics.LENS_FACING_BACK !=
                        cameraCharacteristics.get(CameraCharacteristics.LENS_FACING)) {
                    continue;
                }
                
                StreamConfigurationMap streamConfigurationMap = cameraCharacteristics
                        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                // resolution
                mVideoSize = chooseOptimalSize(streamConfigurationMap.getOutputSizes(MediaRecorder.class), VIDEO_WIDTH,
                        VIDEO_SIZE);
                LOG.debug("Video Size: {}", mVideoSize);
                

                // wasn't working for OnePlus
//                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//                    manager.setTorchMode(cameraId, true);
//                }
    
                return cameraId;
            }
        } catch (CameraAccessException e) {
            LOG.warn("Failed to access camera", e);
            recordingFailed(e);
        }
        return null;
    }

    Observable<CameraDevice> openCameraObservable(CameraManager manager, String cameraId) {

        final CameraDevice[] cameraDevice = new CameraDevice[1];

        return Observable.unsafeCreate(new Observable.OnSubscribe<CameraDevice>() {
            @SuppressLint("MissingPermission")
            @Override
            public void call(Subscriber<? super CameraDevice> subscriber) {
                try {
                    manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                        @Override
                        public void onOpened(@NonNull CameraDevice camera) {
                            LOG.debug("CameraDevice opened");
                            subscriber.onNext(camera);
                            cameraDevice[0] = camera;
                        }

                        public void onClosed(@NonNull CameraDevice camera) {
                            LOG.debug("CameraDevice closed");
                            subscriber.onCompleted();
                        }

                        @Override
                        public void onDisconnected(@NonNull CameraDevice camera) {
                            LOG.debug("CameraDevice disconnected");
                            subscriber.onCompleted();
                        }

                        @Override
                        public void onError(@NonNull CameraDevice camera, int error) {
                            LOG.debug("CameraDevice errored");
                            subscriber.onError(new IllegalStateException());
                        }
                    }, null);
                } catch (CameraAccessException e) {
                    subscriber.onError(e);
                }
            }
        }).cache()
                .doOnUnsubscribe(() -> {
                    if (cameraDevice[0] != null) {
                        LOG.debug("Closing CameraDevice");
                        cameraDevice[0].close();
                    }
                });
    }

    @NonNull
    public static Observable<ImageReader> createImageReaderObservable(final ImageReader imageReader) {
        HandlerThread handlerThread = new HandlerThread("ImageReader thread");
        if (!handlerThread.isAlive()) {
            handlerThread.start();
        }
        return Observable.create((Observable.OnSubscribe<ImageReader>) subscriber -> {
            imageReader.setOnImageAvailableListener(new OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    subscriber.onNext(reader);
                }
            },null);
        }).subscribeOn(AndroidSchedulers.from(handlerThread.getLooper()));
    }

    @NonNull
    public static Observable<CameraCaptureSession>
    createCaptureSessionObservable(
            @NonNull CameraDevice cameraDevice,
            @NonNull List<Surface> surfaceList) {
        final CameraCaptureSession[] captureSession = {null};
        return Observable.<CameraCaptureSession>create
                (new Observable.OnSubscribe<CameraCaptureSession>() {
                    @Override
                    public void call(Subscriber<? super CameraCaptureSession> observableEmitter) {
                        try {
                            cameraDevice.createCaptureSession(surfaceList, new CameraCaptureSession
                                    .StateCallback() {

                                @Override
                                public void onConfigured(@NonNull CameraCaptureSession session) {
                                    LOG.debug("CameraCaptureSession configured");
                                    captureSession[0] = session;
                                    observableEmitter.onNext(session);
                                }

                                @Override
                                public void onConfigureFailed(@NonNull CameraCaptureSession
                                                                      session) {
                                    LOG.debug("Camera session configuration failed");
                                    observableEmitter.onError(new IllegalStateException(
                                            "CameraCaptureSession configuration failed"));
                                }

                                @Override
                                public void onClosed(@NonNull CameraCaptureSession session) {
                                    LOG.debug("CameraCaptureSession closed");
                                    observableEmitter.onCompleted();
                                }

                            }, null);
                        } catch (CameraAccessException e) {
                            observableEmitter.onError(e);
                        }
                    }
                }).cache()
                .doOnUnsubscribe(() -> {
                    if (captureSession[0] != null) {
                        try {
                            LOG.debug("Closing CameraCaptureSession");
                            captureSession[0].close();
                        } catch (IllegalStateException e) {
                            LOG.debug("Couldn't close camera", e);
                        }
                    }
                });
    }
    
    void doRepeatingRequest(@NonNull CameraCaptureSession session, @NonNull List<Surface> surfaces) {
        try {
            CameraCharacteristics cameraCharacteristics = manager.getCameraCharacteristics(session.getDevice().getId());

            for (CameraCharacteristics.Key key : cameraCharacteristics.getKeys()) {
                LOG.debug("Camera characteristics: {}, value: {}", key.getName(), cameraCharacteristics.get
                        (key));
            }

            int[] capabilities = cameraCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            LOG.debug("Camera capabilities: {}", capabilities);

            StreamConfigurationMap streamConfigurationMap = cameraCharacteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            
            // calculate frame rate
            long durationRecorderNs = streamConfigurationMap.getOutputMinFrameDuration(MediaRecorder.class,
                    mVideoSize);
            LOG.debug("Min output frame duration for MediaRecorder: {}", durationRecorderNs);
    
            long durationPreviewNs = streamConfigurationMap.getOutputMinFrameDuration(SurfaceTexture.class,
                    mVideoSize);
            LOG.debug("Min output frame duration for SurfaceTexture: {}", durationPreviewNs);
            int[] outputFormats = streamConfigurationMap.getOutputFormats();
            LOG.debug("Output formats: {}", outputFormats);
            
    
            Range<Long> exposureTimeRange = cameraCharacteristics
                    .get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            LOG.debug("Exposure time range: {}", exposureTimeRange);
    
            Range<Integer> sensitivityTimeRange = cameraCharacteristics
                    .get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            LOG.debug("Sensitivity time range: {}", sensitivityTimeRange);
    
            int[] inputFormats = new int[0];
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                inputFormats = streamConfigurationMap.getInputFormats();
            }
            LOG.debug("Input formats: {}", inputFormats);
            
            LOG.debug("Attempting to create capture request");
            CaptureRequest.Builder requestBuilder = session.getDevice()
                    .createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            for (Surface s : surfaces) {
                requestBuilder.addTarget(s);
            }


            // turns off AF, AE, AWB
            // This prevents the flash from working on Samsung Galaxy J7
            // Turning off each setting individually seems to work fine -Nathaniel 12/18/18
            //requestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF);

            requestBuilder.set( CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_DISABLED );

            // Look at the available camera characteristics to check that CONTROL_AE_MODE_OFF is available.
            int[] availableModes = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
            if (ArrayUtils.contains(availableModes, CaptureRequest.CONTROL_AE_MODE_OFF)) {
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
                LOG.debug("Setting Camera Auto-Exposure to OFF");
            }
            else {
                // TODO: syoung 10/30/2018 FIXME!! Not supported camera (I think). Without the ability to turn off the auto-exposure, we cannot control the frame rate.
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                LOG.warn("WARNING! Camera Settings: Auto-Exposure to ON");
            }

            // CONTROL_AE_ANTIBANDING_MODE
            int[] availableAntibandingModes = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES);
            if (ArrayUtils.contains(availableAntibandingModes, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF)) {
                requestBuilder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF);
                LOG.debug("Camera Settings: Control AE Antibanding Mode to OFF");
            }
            else {
                // TODO: syoung 10/30/2018 FIXME!! Not supported camera (I think).
                LOG.warn("WARNING! Camera Settings: Available Control AE Antibanding Modes {}", availableAntibandingModes);
            }

            // let's not do any AWB for now. seems complex and interacts with AE
            // Has to be off or the COLOR_CORRECTION_TRANSFORM will be ignored
            requestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);

            //Disabling coloring correction transform so we don't get black image on Megha's Galaxy S8 -Nathaniel 05/01/19
//            requestBuilder.set( CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX );
//            int[] cstMatrix = new int[]{ 258, 128, -119, 128, -10, 128, -40, 128, 209, 128, -41, 128, -1, 128, -74, 128, 203, 128 };
//            ColorSpaceTransform cst = new ColorSpaceTransform( cstMatrix );
//            requestBuilder.set( CaptureRequest.COLOR_CORRECTION_TRANSFORM, cst );
            requestBuilder.set( CaptureRequest.SHADING_MODE, CameraMetadata.SHADING_MODE_OFF );
            requestBuilder.set( CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF );

            // COLOR_CORRECTION_ABERRATION_MODE
            int[] availableColorModes = cameraCharacteristics.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES);
            if (ArrayUtils.contains(availableColorModes, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF)) {
                requestBuilder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF);
                LOG.debug("Camera Settings: Color Correction Abberation Mode to OFF");
            }
            else {
                // TODO: syoung 10/30/2018 FIXME!! Not supported camera (I think).
                LOG.warn("WARNING! Camera Settings: Available Color Correction Abberation Modes {}", availableColorModes);
            }

            // Turn everything else OFF. ¯\_(ツ)_/¯ syoung 11/02/2018
            requestBuilder.set( CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF );
            requestBuilder.set( CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_OFF );
            requestBuilder.set( CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF );
            requestBuilder.set( CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_OFF );
            requestBuilder.set( CaptureRequest.SENSOR_TEST_PATTERN_MODE, CameraMetadata.SENSOR_TEST_PATTERN_MODE_OFF );
            requestBuilder.set( CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF );

            // should work on legacy devices
            // no auto-focus infinite, focus distance
            requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
            requestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f);

            // Always set the flash to TORCH to turn it on.
            requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);

            Range<Integer>[] availableRanges = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            Range<Integer> desiredRange = new Range<Integer>(60,60);
            boolean isAvailableRange = ArrayUtils.contains(availableRanges, desiredRange);
            LOG.debug("Available Camera ranges:{}, isAvailableFPS:{}", availableRanges, isAvailableRange);

            requestBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, CAMERA_FRAME_DURATION_NANOS);
            requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, CAMERA_EXPOSURE_DURATION_NANOS);
            requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, CAMERA_SENSITIVITY);

            CaptureRequest captureRequest = requestBuilder.build();
//            for (CaptureRequest.Key<?> k : captureRequest.getKeys()) {
//                Object value = captureRequest.get(k);
//                LOG.debug("Capture request Key: {}, value: {}", k, value);
//            }
    
            session.setRepeatingRequest(captureRequest, mPreCaptureCallback, null);
        } catch (CameraAccessException e) {
            LOG.warn("Failed to set capture request", e);
            recordingFailed(e);
        }
    }
    
    private CameraCaptureSession.CaptureCallback mPreCaptureCallback
            = new CameraCaptureSession.CaptureCallback() {
        Set<CaptureResult.Key> keys = Sets.newHashSet(CONTROL_AE_MODE,CONTROL_AWB_MODE,CONTROL_AF_MODE,
                LENS_FOCAL_LENGTH,SENSOR_SENSITIVITY,
                LENS_FOCUS_DISTANCE,
                SENSOR_EXPOSURE_TIME,SENSOR_FRAME_DURATION
                );
                
                @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
//            for (CaptureResult.Key key : keys) {
//                LOG.debug("Capture progress result with setting key: {}, value: {}", key.getName(), partialResult.get
//                        (key));
//            }
        }
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
//            for (CaptureResult.Key key : keys) {
//                LOG.debug("Capture complete result with setting key: {}, value: {}", key.getName(), result.get(key));
//            }
        }
        public void onCaptureBufferLost(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request, @NonNull Surface target, long frameNumber) {
            LOG.warn("Capture Buffer lost");
        }
    };
    
    private void recordingFailed(Throwable throwable) {
        LOG.warn("Recording failed: ", throwable);
        cancel();
        if (getRecorderListener() != null) {
            getRecorderListener().onFail(this, throwable);
        }
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the respective requested values.
     *
     * @param choices Size options
     * @param width   The minimum desired width
     * @param height  The minimum desired height
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    private static Size chooseOptimalSize(Size[] choices, int width, int height) {

        List<Size> isBigEnough = new ArrayList<>();

        for (Size option : choices) {
            if (option.getWidth() >= width && option.getHeight() >= height) {
                isBigEnough.add(option);
            }
        }

        // Pick the smallest of those, assuming we found any
        if (isBigEnough.size() > 0) {
            return Collections.min(isBigEnough, new CompareSizesByArea());
        } else {
            LOG.warn("Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight()
                    - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    private MediaRecorder createMediaRecorder(Size videoSize, File file) {
        // the lowest available resolution for the first back camera
        try {
            MediaRecorder mediaRecorder = new MediaRecorder();

            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);

            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setOutputFile(file.getAbsolutePath());
            mediaRecorder.setVideoEncodingBitRate(VIDEO_ENCODING_BIT_RATE);
            mediaRecorder.setVideoFrameRate(VIDEO_FRAME_RATE);
            mediaRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
            mediaRecorder.setVideoEncoder(VIDEO_ENCODER);

//            Camera.Parameters p = camera.getParameters();
//            p.setPreviewFpsRange( 30000, 30000 ); // 30 fps
//            if ( p.isAutoExposureLockSupported() )
//                p.setAutoExposureLock( true );
//            camera.setParameters( p );

            return mediaRecorder;
        } catch (Exception e) {
            LOG.warn("Failed to create media recorder", e);
            recordingFailed(e);
        }
        return null;
    }
}
