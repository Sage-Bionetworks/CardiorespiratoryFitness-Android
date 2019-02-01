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

package org.sagebase.crf.step.heartrate;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.AnyThread;
import android.support.annotation.UiThread;

import com.google.gson.JsonObject;

import org.researchstack.backbone.step.Step;
import org.researchstack.backbone.step.active.recorder.JsonArrayDataRecorder;
import org.researchstack.backbone.utils.FormatHelper;
import org.sagebase.crf.step.active.HeartBeatSample;
import org.sagebase.crf.step.active.HeartRateBPM;
import org.sagebase.crf.step.active.HeartRateSampleProcessor;
import org.sagebase.crf.step.active.HeartbeatSampleTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * Created by liujoshua on 2/19/2018.
 */

public interface BpmRecorder {
    
    interface BpmUpdateListener {
        class BpmHolder {
            public final int bpm;
            public final long timestamp;
            
            public BpmHolder(int bpm, long timestamp) {
                this.bpm = bpm;
                this.timestamp = timestamp;
            }
        }
        
        @UiThread
        void bpmUpdate(BpmHolder bpm);
    }
    
    interface IntelligentStartUpdateListener {
        /**
         * @param progress value from 0.0 to 1.0 communicating the progress to being ready
         * @param ready    true if the camera is now collecting data, false otherwise
         */
        void intelligentStartUpdate(float progress, boolean ready);
    }

    interface PressureListener {
        class PressureHolder {
            public final String outputText;
            public final boolean isPressureExcessive;

            public PressureHolder(boolean pressureExcessive, String outputText) {
                this.pressureExcessive = pressureExcessive;
                this.outputText = outputText;
            public PressureHolder(boolean isPressureExcessive) {
                this.isPressureExcessive = isPressureExcessive;
            }

        }
        @UiThread
        void pressureUpdate(PressureHolder pressure);
    }

    interface CameraCoveredListener {
        class CameraCoveredHolder {
            public final boolean isCameraCovered;
            public final boolean cameraCovered;
            public final String outputText;

            public CameraCoveredHolder(boolean isCameraCovered) {
                this.isCameraCovered = isCameraCovered;
            public CameraCoveredHolder(boolean cameraCovered, String outputText) {
                this.cameraCovered = cameraCovered;
                this.outputText = outputText;
            }
        }
        @UiThread
        void cameraUpdate(CameraCoveredHolder camera);

    }

    interface AbnormalHRListener {
        class AbnormalHRHolder {
            public final boolean isAbnormal;

            public AbnormalHRHolder(boolean isAbnormal) {
                this.isAbnormal = isAbnormal;
            }
        }

        @UiThread
        void abnormalHRUpdate(AbnormalHRHolder abnormal);
    }

    interface DeclineHRListener {
        class DeclineHRHolder {
            public final boolean isDeclining;

            public DeclineHRHolder(boolean isDeclining) {
                this.isDeclining = isDeclining;
            }
        }

        @UiThread
        void declineHRUpdate(DeclineHRHolder decline);
    }

    class BpmCalculator {
        
        private static final Logger LOG = LoggerFactory.getLogger(BpmCalculator.class);

        private final HeartRateSampleProcessor sampleProcessor = new HeartRateSampleProcessor();

        ;

        private static final int BEATS_ARRAY_SIZE = 3;
        private static final int AVERAGE_ARRAY_SIZE = 4;
        private static int beatsIndex = 0;

        private TYPE currentType = TYPE.GREEN;
        private int beats = 0;
        private int averageIndex = 0;

        private final int[] beatsArray = new int[BEATS_ARRAY_SIZE];
        private final int[] averageArray = new int[AVERAGE_ARRAY_SIZE];

        private double startTime = -1;


        /**
         * Calculates a simple running average bpm to display to the user for their heart rate. Updates the sample
         * with the calculated bpm, if there is one.
         *
         * @param heartBeatSample heartBeatSample
         */
        public void calculateBpm(HeartBeatSample heartBeatSample) {
            sampleProcessor.addSample(heartBeatSample);
            if (!sampleProcessor.isReadyToProcess()) {
                return;
            }

            HeartRateBPM ret = sampleProcessor.processSamples();

            LOG.debug("HeartRateBPM {}", ret);
            heartBeatSample.bpm = ret.getBpm();
            heartBeatSample.confidence = ret.getConfidence();
        }
    }
    
    class HeartBeatJsonWriter extends JsonArrayDataRecorder
            implements HeartbeatSampleTracker.HeartRateUpdateListener {
        
        private static final Logger LOG = LoggerFactory.getLogger(HeartBeatJsonWriter.class);
        
        private static final float RED_INTENSITY_FACTOR_THRESHOLD = 2;
        private static final String TIMESTAMP_DATE_KEY = "timestampDate";
        private static final String TIMESTAMP_IN_SECONDS_KEY = "timestamp";
        private static final String UPTIME_IN_SECONDS_KEY = "uptime";
        private static final String HEART_RATE_KEY = "bpm_camera";
        private static final String HUE_KEY = "hue";
        private static final String SATURATION_KEY = "saturation";
        private static final String BRIGHTNESS_KEY = "brightness";
        private static final String RED_KEY = "red";
        private static final String GREEN_KEY = "green";
        private static final String BLUE_KEY = "blue";
        private static final String RED_LEVEL_KEY = "redLevel";

        private static final int INTELLIGENT_START_FRAMES_TO_PASS = 60;
        
        private final JsonObject mJsonObject = new JsonObject();

        private double timestampZeroReference = -1;
        private double uptimeZeroReference = -1;

        /**
         * Intelligent start is a feature that delays recording until
         * an algorithm determines the user's finger is in front of the camera
         * Disabled by default
         */
        private boolean mEnableIntelligentStart = true;
        private boolean mIntelligentStartPassed = false;
        private int mIntelligentStartCounter = 0;
        private boolean isRecordingStarted = false;
        
        private final BpmRecorder.BpmUpdateListener mBpmUpdateListener;
        private final BpmRecorder.IntelligentStartUpdateListener mIntelligentStartListener;
        private final BpmRecorder.PressureListener mPressureListener;
        private final BpmRecorder.CameraCoveredListener mCameraListener;
        private final BpmRecorder.AbnormalHRListener mAbnormalListener;
        private final BpmRecorder.DeclineHRListener mDeclineListener;

        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        
        private final BpmCalculator bpmCalculator;
        
        public HeartBeatJsonWriter(BpmUpdateListener
                                           mBpmUpdateListener, IntelligentStartUpdateListener
                                           mIntelligentStartListener,
                                   CameraCoveredListener mCameraListener,
                                   PressureListener mPressureListener, AbnormalHRListener mAbnormalListener,
                                   DeclineHRListener mDeclineListener, String identifier,
                                   Step step, File outputDirectory) {
            super(identifier, step, outputDirectory);
            
            this.mBpmUpdateListener = mBpmUpdateListener;
            this.mIntelligentStartListener = mIntelligentStartListener;
            this.mPressureListener = mPressureListener;
            this.mCameraListener = mCameraListener;
            this.bpmCalculator = new BpmCalculator();
            this.mAbnormalListener = mAbnormalListener;
            this.mDeclineListener = mDeclineListener;
        }

        private int sampleCount = 0;
        private double timestampReference = -1;

        @AnyThread
        @Override
        public void onHeartRateSampleDetected(HeartBeatSample sample) {
            bpmCalculator.calculateBpm(sample);

            if (timestampZeroReference < 0) {
                // set timestamp reference, which timestamps are measured relative to
                timestampZeroReference = sample.t;
                uptimeZeroReference = System.nanoTime() * 1e-9;

                Date timestampReferenceDate = new Date(System.currentTimeMillis());
                mJsonObject.addProperty(TIMESTAMP_DATE_KEY,
                        new SimpleDateFormat(FormatHelper.DATE_FORMAT_ISO_8601, new Locale("en", "us", "POSIX"))
                                .format(timestampReferenceDate));
                LOG.debug("TIMESTAMP Date key: " + mJsonObject.get(TIMESTAMP_DATE_KEY).getAsString());
            } else {
                mJsonObject.remove(TIMESTAMP_DATE_KEY);
            }

            double relativeTimestamp = ((sample.t - timestampZeroReference) / 1_000);
            double uptime = uptimeZeroReference + relativeTimestamp;

            mJsonObject.addProperty(TIMESTAMP_IN_SECONDS_KEY, relativeTimestamp);
            mJsonObject.addProperty(UPTIME_IN_SECONDS_KEY, uptime);
            mJsonObject.addProperty(HUE_KEY, sample.h);
            mJsonObject.addProperty(SATURATION_KEY, sample.s);
            mJsonObject.addProperty(BRIGHTNESS_KEY, sample.v);
            mJsonObject.addProperty(RED_KEY, sample.r);
            mJsonObject.addProperty(GREEN_KEY, sample.g);
            mJsonObject.addProperty(BLUE_KEY, sample.b);
            mJsonObject.addProperty(RED_LEVEL_KEY, sample.redLevel);

            if (sample.bpm > 0) {
                mJsonObject.addProperty(HEART_RATE_KEY, sample.bpm);
                if (mBpmUpdateListener != null) {
                    mainHandler.post(() ->
                            mBpmUpdateListener.bpmUpdate(
                                    new BpmRecorder.BpmUpdateListener.BpmHolder(sample.bpm, (long)sample.t)));
                }
            } else {
                mJsonObject.remove(HEART_RATE_KEY);
            }


            if (LOG.isTraceEnabled()) {
                LOG.trace("HeartBeatSample: {}", sample);
            }

            if (!mEnableIntelligentStart || mIntelligentStartPassed) {
                writeJsonObjectToFile(mJsonObject);
            } else {
                updateIntelligentStart(sample);
            }
        }
        
        private void updateIntelligentStart(HeartBeatSample sample) {
            if (mIntelligentStartPassed) {
                return; // we already computed that we could start
            }

            // When a finger is placed in front of the camera with the flash on,
            // the camera image will be almost entirely red, so use a simple lenient algorithm
            // for this
            float greenBlueSum = sample.g + sample.b;
            if (greenBlueSum == 0.0f) {
                greenBlueSum = 0.0000000001f;
            }
            float redFactor = sample.r / greenBlueSum;

            // If the red factor is large enough, we update the trigger
            if (redFactor >= RED_INTENSITY_FACTOR_THRESHOLD) {
                mIntelligentStartCounter++;
                if (mIntelligentStartCounter >= INTELLIGENT_START_FRAMES_TO_PASS) {
                    mIntelligentStartPassed = true;
                }
                if (mIntelligentStartListener != null) {
                    float progress = (float) mIntelligentStartCounter / (float)
                            INTELLIGENT_START_FRAMES_TO_PASS;
    
                    mainHandler.post(() ->
                            mIntelligentStartListener.intelligentStartUpdate(progress,
                                    mIntelligentStartPassed)
                    );

                }
                if (mCameraListener != null) {
                    mainHandler.post(() ->
                            mCameraListener.cameraUpdate(new
                                    BpmRecorder.CameraCoveredListener.CameraCoveredHolder(true)));
                }
                if (mAbnormalListener != null) {
                    if(sample.abnormalHR()) {
                        mainHandler.post(() ->
                                mAbnormalListener.abnormalHRUpdate(new
                                        BpmRecorder.AbnormalHRListener.AbnormalHRHolder(true)));
                    }
                }
                if (mDeclineListener != null) {
                    if(sample.declineHR()) {
                        mainHandler.post(() ->
                                mDeclineListener.declineHRUpdate(new
                                        DeclineHRListener.DeclineHRHolder(true)));
                    }
                }

                if (mPressureListener != null) {
                    if(sample.isPressureExcessive() && !sample.declineHR()) {
                        mainHandler.post(() ->
                                mPressureListener.pressureUpdate(new
                                        BpmRecorder.PressureListener.PressureHolder(true)));
                    }
                    else {
                        mainHandler.post(() ->
                                mPressureListener.pressureUpdate(new
                                        BpmRecorder.PressureListener.PressureHolder(false)));
                    }
                }


            } else {  // We need thresholds to be passed sequentially otherwise it is restarted
                mIntelligentStartCounter = 0;

                if (mCameraListener != null) {
                    mainHandler.post(() ->
                            mCameraListener.cameraUpdate(new
                                    BpmRecorder.CameraCoveredListener.CameraCoveredHolder(false)));
                }
            }
        }
        
        @Override
        public void start(Context context) {
            startJsonDataLogging();
            isRecordingStarted = true;
            mIntelligentStartPassed = false;
            mIntelligentStartCounter = 0;
        }
        
        @Override
        public void stop() {
            if (isRecordingStarted) {
                isRecordingStarted = false;
                stopJsonDataLogging();
            }
        }
        
    }
}
