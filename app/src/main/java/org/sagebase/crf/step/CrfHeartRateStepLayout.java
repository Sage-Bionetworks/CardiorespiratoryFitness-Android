/*
 *    Copyright 2017 Sage Bionetworks
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

package org.sagebase.crf.step;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Path;
import android.support.v4.content.res.ResourcesCompat;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.Transformation;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import org.researchstack.backbone.answerformat.AnswerFormat;
import org.researchstack.backbone.answerformat.DecimalAnswerFormat;
import org.researchstack.backbone.result.Result;
import org.researchstack.backbone.result.StepResult;
import org.researchstack.backbone.result.TaskResult;
import org.researchstack.backbone.step.QuestionStep;
import org.researchstack.backbone.step.Step;
import org.researchstack.backbone.step.active.recorder.Recorder;
import org.researchstack.backbone.step.active.recorder.RecorderConfig;
import org.researchstack.backbone.ui.callbacks.StepCallbacks;
import org.researchstack.backbone.ui.step.layout.ActiveStepLayout;
import org.researchstack.backbone.ui.views.ArcDrawable;
import org.researchstack.backbone.utils.StepResultHelper;
import org.sagebase.crf.camera.CameraSourcePreview;
import org.sagebase.crf.step.active.HeartRateCameraRecorder;
import org.sagebase.crf.step.active.HeartRateCameraRecorderConfig;

import org.sagebase.crf.view.CrfTaskStatusBarManipulator;
import org.sagebase.crf.view.CrfTaskToolbarTintManipulator;
import org.sagebionetworks.research.crf.R;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Created by TheMDP on 10/31/17.
 */

public class CrfHeartRateStepLayout extends ActiveStepLayout implements
        HeartRateCameraRecorder.BpmUpdateListener,
        HeartRateCameraRecorder.IntelligentStartUpdateListener,
        CrfTaskToolbarTintManipulator,
        CrfTaskStatusBarManipulator,
        CrfResultListener {

    private static final String AVERAGE_BPM_IDENTIFIER = "AVERAGE_BPM_IDENTIFIER";
    private static final String BPM_START_IDENTIFIER_SUFFIX = ".heartRate_start";
    private static final String BPM_END_IDENTIFIER_SUFFIX = ".heartRate_end";

    private CameraSourcePreview cameraSourcePreview;

    protected TextView crfMessageTextView;

    protected View heartRateTextContainer;
    protected TextView heartRateNumber;

    protected View arcDrawableContainer;
    protected View arcDrawableView;
    protected ArcDrawable arcDrawable;

    protected Button nextButton;
    protected ImageView heartImageView;
    protected HeartBeatAnimation heartBeatAnimation;

    // The previousBpm comes from the TaskResult of an unrelated CrfHeartRateStepLayout
    private int previousBpm = -1;

    private boolean hasDetectedStart = false;
    private List<BpmHolder> bpmList;

    public CrfHeartRateStepLayout(Context context) {
        super(context);
    }

    public CrfHeartRateStepLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CrfHeartRateStepLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public CrfHeartRateStepLayout(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public int getContentResourceId() {
        return R.layout.crf_step_layout_heart_rate;
    }

    @Override
    public int getContentContainerLayoutId() {
        return R.id.crf_step_layout_container;
    }

    @Override
    public int getFixedSubmitBarLayoutId() {
        return R.layout.crf_step_layout_container;
    }

    @Override
    public void setupActiveViews() {
        super.setupActiveViews();
        crfMessageTextView = findViewById(R.id.crf_heart_rate_title);
        crfMessageTextView.setText(R.string.crf_camera_cover);

        cameraSourcePreview = findViewById(R.id.crf_camera_source);
        cameraSourcePreview.setSurfaceMask(true);
        cameraSourcePreview.setCameraSizeListener((width, height) -> {
            ViewGroup.LayoutParams params = arcDrawableContainer.getLayoutParams();
            int size = Math.min(width, height);
            params.width = size;
            params.height = size;
            arcDrawableContainer.setLayoutParams(params);
            arcDrawableContainer.requestLayout();
        });

        heartRateTextContainer = findViewById(R.id.crf_bpm_text_container);
        heartRateTextContainer.setVisibility(View.GONE);
        heartRateNumber = findViewById(R.id.crf_heart_rate_number);

        arcDrawableContainer = findViewById(R.id.crf_arc_drawable_container);
        arcDrawableView = findViewById(R.id.crf_arc_drawable);
        arcDrawable = new ArcDrawable();
        arcDrawable.setColor(ResourcesCompat.getColor(getResources(), R.color.greenyBlue, null));
        arcDrawable.setArchWidth(getResources().getDimensionPixelOffset(R.dimen.crf_ard_drawable_width));
        arcDrawable.setDirection(Path.Direction.CW);
        arcDrawableView.setBackground(arcDrawable);

        nextButton = findViewById(R.id.crf_next_button);
        nextButton.setVisibility(View.GONE);
        nextButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                callbacks.onSaveStep(StepCallbacks.ACTION_NEXT, activeStep, stepResult);
            }
        });

        heartImageView = findViewById(R.id.crf_heart_icon);
        heartImageView.setVisibility(View.GONE);
    }

    @Override
    public void start() {
        hasDetectedStart = false;
        bpmList = new ArrayList<>();

        super.start();

        // If the camera was not set up properly,
        if (!cameraSourcePreview.isCameraSetup()) {
            showOkAlertDialog("Error opening camera interface", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    callbacks.onSaveStep(StepCallbacks.ACTION_PREV, activeStep, null);
                }
            });
        }
    }

    @Override
    public Recorder createCustomRecorder(RecorderConfig config) {
        if (config instanceof HeartRateCameraRecorderConfig) {
            HeartRateCameraRecorderConfig heartRateConfig = (HeartRateCameraRecorderConfig)config;
            HeartRateCameraRecorder recorder = (HeartRateCameraRecorder)heartRateConfig.recorderForStep(
                    cameraSourcePreview, activeStep, getOutputDirectory(getContext()));
            recorder.setEnableIntelligentStart(true);
            recorder.setIntelligentStartListener(this);
            recorder.setBpmUpdateListener(this);
            return recorder;
        }
        return null;
    }

    // BPM and heart rate is ready to go, switch the UI
    private void intelligentStartDetected() {
        hasDetectedStart = true;
        heartImageView.setVisibility(View.VISIBLE);
        heartRateTextContainer.setVisibility(View.VISIBLE);
        arcDrawableContainer.setVisibility(View.VISIBLE);
        arcDrawable.setSweepAngle(0.0f);
        heartRateNumber.setText("--");
        startAnimation();  // this will trigger a restart of the timer
    }

    @Override
    public void doUIAnimationPerSecond() {
        if (hasDetectedStart) {
            float progress = 1.0f - ((float)secondsLeft / (float)activeStep.getStepDuration());
            arcDrawable.setSweepAngle(ArcDrawable.FULL_SWEEPING_ANGLE * progress);

            if (secondsLeft <= 7) {
                crfMessageTextView.setText(R.string.crf_camera_7_sec);
            } else if (secondsLeft <= 15) {
                crfMessageTextView.setText(R.string.crf_camera_15_sec);
            } else if (secondsLeft <= 30) {
                crfMessageTextView.setText(R.string.crf_camera_half_way);
            } else {
                crfMessageTextView.setText(R.string.crf_camera_keep_still);
            }
        }
    }

    public void bpmUpdate(BpmHolder bpmHolder) {
        heartRateNumber.setText(String.format(Locale.getDefault(), "%d", bpmHolder.bpm));
        if (heartBeatAnimation == null) {
            heartBeatAnimation = new HeartBeatAnimation(bpmHolder.bpm);
            heartImageView.startAnimation(heartBeatAnimation);
        }
        heartBeatAnimation.setBpm(bpmHolder.bpm);
        bpmList.add(bpmHolder);
    }

    @Override
    public void onComplete(Recorder recorder, Result result) {
        super.onComplete(recorder, result);

        nextButton.setVisibility(View.VISIBLE);
        heartImageView.setVisibility(View.GONE);
        crfMessageTextView.setText(R.string.crf_camera_done);
        cameraSourcePreview.setVisibility(View.INVISIBLE);
        arcDrawableContainer.setVisibility(View.GONE);

        if (!bpmList.isEmpty()) {
            int bpmSum = 0;
            for (BpmHolder bpmHolder : bpmList) {
                bpmSum += bpmHolder.bpm;
            }
            int averageBpm = bpmSum / bpmList.size();
            heartRateNumber.setText(String.format(Locale.getDefault(), "%d", averageBpm));
            setBpmDifferenceResult(averageBpm);
            setBpmStartAndEnd(bpmList.get(0), bpmList.get(bpmList.size()-1));
        } else {
            heartRateNumber.setText(String.format(Locale.getDefault(), "%d", 0));
            setBpmDifferenceResult(0);
        }
    }

    /**
     * Saves the first and last BPM readings of the step
     * @param bpmStart first BPM reading recorded
     * @param bpmEnd last BPM reading recorded
     */
    private void setBpmStartAndEnd(BpmHolder bpmStart, BpmHolder bpmEnd) {
        String startIdentifier = activeStep.getIdentifier() + BPM_START_IDENTIFIER_SUFFIX;
        stepResult.setResultForIdentifier(startIdentifier,
                getBpmStepResult(startIdentifier, bpmStart));

        String endIdentifier = activeStep.getIdentifier() + BPM_END_IDENTIFIER_SUFFIX;
        stepResult.setResultForIdentifier(endIdentifier,
                getBpmStepResult(endIdentifier, bpmEnd));
    }

    private StepResult<Integer> getBpmStepResult(String identifier, BpmHolder bpmHolder) {
        QuestionStep bpmQuestion =
                new QuestionStep(identifier, identifier, new DecimalAnswerFormat(0,300));
        StepResult<Integer> bpmResult = new StepResult<>(bpmQuestion);
        bpmResult.setResult(bpmHolder.bpm);
        bpmResult.setStartDate(new Date(bpmHolder.timestamp));
        bpmResult.setEndDate(new Date(bpmHolder.timestamp));

        return bpmResult;
    }

    /**
     * The BPM result will be stored in the TaskResult and if there are multiple
     * CrfHeartRateSteps, the difference between the BPMs will be stored in the result as well
     * @param bpm the average BPM
     */
    private void setBpmDifferenceResult(int bpm) {
        // See if we have a previous BPM, in which case we should calculate the difference
        if (previousBpm >= 0) {
            String bpmStepId = CrfCompletionStepLayout.COMPLETION_BPM_VALUE_RESULT;
            StepResult<String> bpmResult = new StepResult<>(new Step(bpmStepId));
            int bpmDifference = Math.abs(bpm - previousBpm);
            bpmResult.setResult(String.valueOf(bpmDifference));
            stepResult.setResultForIdentifier(bpmStepId, bpmResult);
        } else {
            String bpmStepId = CrfHeartRateStepLayout.AVERAGE_BPM_IDENTIFIER;
            StepResult<String> bpmResult = new StepResult<>(new Step(bpmStepId));
            bpmResult.setResult(String.valueOf(bpm));
            stepResult.setResultForIdentifier(bpmStepId, bpmResult);
        }
    }

    @Override
    public void intelligentStartUpdate(float progress, boolean ready) {
        if (ready) {
            intelligentStartDetected();
        }
    }

    @Override
    public int crfToolbarTintColor() {
        return R.color.azure;
    }

    @Override
    public int crfStatusBarColor() {
        return R.color.white;
    }

    @Override
    public void crfTaskResult(TaskResult taskResult) {
        String bpmString = StepResultHelper.findStringResult(taskResult, AVERAGE_BPM_IDENTIFIER);
        if (bpmString != null) {
            previousBpm = Integer.parseInt(bpmString);
        }
    }

    private class HeartBeatAnimation extends AlphaAnimation {

        void setBpm(int bpm) {
            setDuration((long)((60.0f / (float)bpm) * 1000));
        }

        HeartBeatAnimation(int bpm) {
            super(1.0f, 1.0f);
            setBpm(bpm);
            setInterpolator(new AccelerateInterpolator());
            setRepeatCount(Animation.INFINITE);
        }

        @Override
        protected void applyTransformation(float interpolatedTime, Transformation t) {
            float alpha;
            if (interpolatedTime < 0.5f) { // we are fading out
                alpha = (2 * (0.5f - interpolatedTime));
            } else {
                alpha = (2 * (interpolatedTime - 0.5f));
            }
            t.setAlpha(alpha);
        }
    }
}
