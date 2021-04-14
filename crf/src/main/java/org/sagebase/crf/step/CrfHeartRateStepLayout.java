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

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.SurfaceTexture;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.res.ResourcesCompat;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.AttributeSet;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.Transformation;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import org.sagebionetworks.researchstack.backbone.answerformat.DecimalAnswerFormat;
import org.sagebionetworks.researchstack.backbone.answerformat.IntegerAnswerFormat;
import org.sagebionetworks.researchstack.backbone.answerformat.TextAnswerFormat;
import org.sagebionetworks.researchstack.backbone.result.Result;
import org.sagebionetworks.researchstack.backbone.result.StepResult;
import org.sagebionetworks.researchstack.backbone.result.TaskResult;
import org.sagebionetworks.researchstack.backbone.step.QuestionStep;
import org.sagebionetworks.researchstack.backbone.step.Step;
import org.sagebionetworks.researchstack.backbone.step.active.recorder.JsonArrayDataRecorder;
import org.sagebionetworks.researchstack.backbone.step.active.recorder.Recorder;
import org.sagebionetworks.researchstack.backbone.step.active.recorder.RecorderListener;
import org.sagebionetworks.researchstack.backbone.ui.callbacks.StepCallbacks;
import org.sagebionetworks.researchstack.backbone.ui.step.layout.ActiveStepLayout;
import org.sagebionetworks.researchstack.backbone.ui.views.ArcDrawable;
import org.sagebionetworks.researchstack.backbone.utils.StepResultHelper;
import org.sagebase.crf.R;
import org.sagebase.crf.camera.CameraSourcePreview;
import org.sagebase.crf.step.active.BpmRecorder;
import org.sagebase.crf.step.active.HeartRateCamera2Recorder;
import org.sagebase.crf.step.active.HeartRateCameraRecorder;
import org.sagebase.crf.step.active.HeartRateCameraRecorderConfig;
import org.sagebase.crf.step.active.Sex;
import org.sagebase.crf.view.CrfTaskToolbarProgressManipulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Created by TheMDP on 10/31/17.
 */

public class CrfHeartRateStepLayout extends ActiveStepLayout implements
        BpmRecorder.BpmUpdateListener,
        BpmRecorder.IntelligentStartUpdateListener,
        BpmRecorder.CameraCoveredListener,
        BpmRecorder.PressureListener,
        BpmRecorder.DeclineHRListener,
        BpmRecorder.AbnormalHRListener,
        RecorderListener,
        CrfTaskToolbarProgressManipulator,
        CrfResultListener {
    private static final Logger LOG = LoggerFactory.getLogger(CrfHeartRateStepLayout.class);

    protected CrfHeartRateCameraStep step;

    public static final String RESTING_BPM_VALUE_RESULT = "resting";
    public static final String RESTING_CONFIDENCE_VALUE_RESULT = "resting_confidence";
    public static final String PEAK_BPM_VALUE_RESULT = "peak";
    public static final String PEAK_BPM_CONFIDENCE_RESULT = "peak_confidence";
    public static final String VO2_MAX_VALUE_RESULT = "vo2_max";
    public static final String VO2_MAX_RANGE_RESULT = "vo2_max_range";
    public static final String RESULT_STATUS = "result_status";
    public static final String STATUS_FAILED = "failed";

    private static double MINIMUM_CONFIDENCE = 0.5;

    private CameraSourcePreview cameraSourcePreview;
    private TextureView cameraPreview;
    public TextureView getCameraPreview() {
        return cameraPreview;
    }
    protected TextView crfMessageTextView;
    protected TextView crfOops;

    protected View heartRateTextContainer;
    protected TextView heartRateNumber;

    protected TextView currentHeartRate;
    protected TextView calculateSuccess;
    protected TextView bpmText;

    protected View heartContainer;

    protected View arcDrawableContainer;
    protected View arcDrawableView;
    protected ArcDrawable arcDrawable;
    protected RelativeLayout layout;

    protected ConstraintLayout buttonContainer;
    protected Button nextButton;
    protected Button redoButton;

    protected ImageView heartImageView;
    protected HeartBeatAnimation heartBeatAnimation;

    protected ImageView crfCompletionIcon;
    protected TextView crfPractice;
    protected TextView coverFlash;
    protected TextView yourHRis;
    protected TextView finalBpm;
    protected TextView finalBpmText;

    private boolean hasDetectedStart = false;
    private List<BpmHolder> bpmList;

    protected  Recorder cameraRecorder;
    protected boolean shouldContinueOnStop = false;
    protected boolean isFinished = false;
    private boolean shouldShowFinishUi = false;

    private boolean cameraCoverd = false;

    private String sex;
    private int birthYear;

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
    public void initialize(Step step, StepResult result) {
        this.step = (CrfHeartRateCameraStep) step;
        super.initialize(step, result);
    }

    @Override
    public void setupActiveViews() {
        super.setupActiveViews();

        shouldShowFinishUi = getResources().getBoolean(R.bool.heart_rate_show_finish_ui);

        cameraPreview = findViewById(R.id.crf_camera_texture_view);

        crfOops = findViewById(R.id.crf_oops);
        crfOops.setText(R.string.crf_oops);
        crfOops.setVisibility(View.INVISIBLE);

        bpmText = findViewById(R.id.crf_heart_rate_bpm);

        crfMessageTextView = findViewById(R.id.crf_heart_rate_title);
        speakText(getContext().getString(R.string.crf_camera_cover));
        crfMessageTextView.setText(R.string.crf_camera_cover);
        if (shouldShowFinishUi) {
            //Remove the padding at the top for the progress bar, that is not shown in this case
            crfMessageTextView.setPadding(crfMessageTextView.getPaddingLeft(), 0, crfMessageTextView.getPaddingRight(), crfMessageTextView.getPaddingBottom());
        }

        cameraSourcePreview = findViewById(R.id.crf_camera_source);
        cameraSourcePreview.setSurfaceMask(true);
        if (cameraRecorder instanceof HeartRateCameraRecorder) {
            cameraSourcePreview.setCameraSizeListener((width, height) -> {
                ViewGroup.LayoutParams params = arcDrawableContainer.getLayoutParams();
                int size = Math.min(width, height);
                params.width = size;
                params.height = size;
                arcDrawableContainer.setLayoutParams(params);
                arcDrawableContainer.requestLayout();
            });
        }

        heartRateTextContainer = findViewById(R.id.crf_bpm_text_container);
        heartRateTextContainer.setVisibility(View.GONE);
        heartRateNumber = findViewById(R.id.crf_heart_rate_number);
        currentHeartRate = findViewById(R.id.crf_current_bpm);

        arcDrawableContainer = findViewById(R.id.crf_arc_drawable_container);
        arcDrawableView = findViewById(R.id.crf_arc_drawable);
        arcDrawable = new ArcDrawable();
        arcDrawable.setColor(ResourcesCompat.getColor(getResources(), R.color.colorAccent, null));
        arcDrawable.setArchWidth(getResources().getDimensionPixelOffset(R.dimen.crf_ard_drawable_width));
        arcDrawable.setDirection(Path.Direction.CW);
        arcDrawable.setIncludeFullCirclePreview(true);
        arcDrawable.setFullCirclePreviewColor(ResourcesCompat.getColor(getResources(), R.color.silver, null));
        arcDrawableView.setBackground(arcDrawable);

        layout = findViewById(R.id.crf_root_instruction_layout);

        heartContainer = findViewById(R.id.crf_heart_container);
        heartContainer.setBackgroundColor(getResources().getColor(R.color.white));

        nextButton = findViewById(R.id.button_go_forward);
        nextButton.setVisibility(View.GONE);
        nextButton.setOnClickListener(view -> onNextButtonClicked());

        calculateSuccess = findViewById(R.id.crf_calculate);
        calculateSuccess.setVisibility(View.INVISIBLE);

        redoButton = findViewById(R.id.crf_redo_button);
        redoButton.setVisibility(View.GONE);
        redoButton.setOnClickListener(view -> onNextButtonClicked());

        heartImageView = findViewById(R.id.crf_heart_icon);
        heartImageView.setVisibility(View.GONE);

        buttonContainer = findViewById(R.id.crf_next_button_container);

        crfCompletionIcon = findViewById(R.id.crf_completion_icon);
        crfPractice = findViewById(R.id.crf_practice);
        coverFlash = findViewById(R.id.crf_later_tests);
        yourHRis = findViewById(R.id.crf_your_hr_is);
        finalBpm = findViewById(R.id.crf_final_bpm);
        finalBpmText = findViewById(R.id.crf_bpm_text);

    }

    // Wait for intelligent start to call super.start()
    // super.start();
    @SuppressLint("MissingSuperCall")
    @Override
    public void start() {
        // Wait for intelligent start to
        hasDetectedStart = false;
        bpmList = new ArrayList<>();

        HeartRateCameraRecorderConfig config =
                new HeartRateCameraRecorderConfig(step.stepIdentifier);
        cameraRecorder = config.recorderForStep(
                cameraSourcePreview, activeStep, this, getOutputDirectory(getContext()));
        cameraRecorder.setRecorderListener(this);

        // camera1
        // If the camera was not set up properly,
        if (cameraRecorder instanceof HeartRateCameraRecorder) {
            cameraRecorder.start(getContext().getApplicationContext());
            if(!cameraSourcePreview.isCameraSetup()) {
                showOkAlertDialog("Error opening camera interface", (dialogInterface, i) ->
                        callbacks.onSaveStep(StepCallbacks.ACTION_PREV, activeStep, null));
            }
        }

        // camera2
        if((cameraRecorder instanceof HeartRateCamera2Recorder)) {
            cameraSourcePreview.setVisibility(GONE);
            startRecorderForTextureView();
        }
    }

    private void startRecorderForTextureView() {
        if (cameraPreview.isAvailable()) {
            cameraRecorder.start(getContext().getApplicationContext());
            return;
        }
        cameraPreview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                cameraRecorder.start(getContext().getApplicationContext());
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {

            }
        });
    }

    @Override
    public void pauseActiveStepLayout() {
        super.pauseActiveStepLayout();
        if (!isFinished) { // pause happens when we've finished too. forceStop deletes the .mp4
            forceStop();  // we do not allow this step to run in the background
            callbacks.onSaveStep(StepCallbacks.ACTION_PREV, activeStep, null);
        }
    }

    @Override
    public void forceStop() {
        super.forceStop();
        if (cameraRecorder != null && cameraRecorder.isRecording()) {
            cameraRecorder.cancel();
        }
    }

    // BPM and heart rate is ready to go, switch the UI
    private void intelligentStartDetected() {
        // Testing
        heartImageView.setVisibility(View.VISIBLE);
        arcDrawableContainer.setVisibility(View.VISIBLE);
        arcDrawable.setSweepAngle(0.0f);
        cameraPreview.setVisibility(View.GONE);
        cameraSourcePreview.setVisibility(View.GONE);

        hasDetectedStart = true;

        if(cameraRecorder instanceof  HeartRateCamera2Recorder) {
            //Due to privacy concerns, and not currently having a scientific use for the raw video
            //disable video recording
//            startVideoRecording();
        }
        super.start();  // start the recording process

        // We need to stop the camera recorder ourselves
        mainHandler.postDelayed(() -> cameraRecorder.stop(),
                activeStep.getStepDuration() * 1000L);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    void startVideoRecording() {
        //HACK for Samsung Galaxy J7 Neo that records 0 bpm when recording video
        String device = Build.MANUFACTURER + Build.MODEL;
        if ("samsungSM-J701M".equalsIgnoreCase(device)) {
            //TODO: Figure out a better solution if there are other devices that can't record video and heart rate at same time
            // -Nathaniel 12/18/18
            return;
        }

        ((HeartRateCamera2Recorder) cameraRecorder).startVideoRecording();

    }

    @Override
    public void doUIAnimationPerSecond() {
        if (hasDetectedStart) {
            float progress = 1.0f - ((float)secondsLeft / (float)activeStep.getStepDuration());
            arcDrawable.setSweepAngle(ArcDrawable.FULL_SWEEPING_ANGLE * progress);
        }
    }

    @Override
    protected void recorderServiceSpokeText(String spokenText) {
        super.recorderServiceSpokeText(spokenText);
        crfMessageTextView.setText(spokenText);
    }

    @UiThread
    public void bpmUpdate(BpmHolder bpmHolder) {
        if (heartBeatAnimation == null) {
            heartBeatAnimation = new HeartBeatAnimation(bpmHolder.bpm);
            heartImageView.startAnimation(heartBeatAnimation);
            heartImageView.setVisibility(VISIBLE);
        }
        String bpmText = "--";
        if (bpmHolder.confidence >= MINIMUM_CONFIDENCE) {
            bpmText = String.valueOf(bpmHolder.bpm);
        }
        currentHeartRate.setText(bpmText + " " + getContext().getString(R.string.crf_bpm));
        arcDrawableContainer.setVisibility(VISIBLE);
        currentHeartRate.setVisibility(VISIBLE);
        heartBeatAnimation.setBpm(bpmHolder.bpm);
        bpmList.add(bpmHolder);
    }

    @Override
    public void stop() {
        super.stop();

        isFinished = true;
        if (shouldContinueOnStop) {
            onNextButtonClicked();
        }
    }

    protected void onNextButtonClicked() {
        if (shouldShowFinishUi) {
            showFinishUi();
        } else {
            shouldContinueOnStop = true;
            if (isFinished) {
                callbacks.onSaveStep(StepCallbacks.ACTION_NEXT, activeStep, stepResult);
            }
        }
    }

    protected void onDoneButtonClicked() {
        String statusId = RESULT_STATUS;
        StepResult<String> result = new StepResult<>(new Step(statusId));
        result.setResult(STATUS_FAILED);
        stepResult.setResultForIdentifier(statusId, result);

        callbacks.onSaveStep(StepCallbacks.ACTION_NEXT, activeStep, stepResult);
    }

    public void onRedoButtonClicked() {
        pauseActiveStepLayout();
        forceStop();
        callbacks.onSaveStep(StepCallbacks.ACTION_PREV, activeStep, null);
    }

    protected void showFailureUi() {
        crfOops.setText(R.string.crf_sorry);
        crfOops.setVisibility(View.VISIBLE);
        nextButton.setVisibility(View.VISIBLE);
        //heartRateTextContainer.setVisibility(View.VISIBLE);
        calculateSuccess.setVisibility(View.VISIBLE);
        arcDrawableContainer.setVisibility(View.VISIBLE);
        arcDrawable.setSweepAngle(0.0f);

        String troubleString = getResources().getString(R.string.crf_having_trouble);
        int startIndex = troubleString.indexOf("Tips");
        int endIndex = troubleString.length();
        SpannableString troubleSpannable = new SpannableString(troubleString);
        troubleSpannable.setSpan(new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                Intent i = new Intent(getContext(), CrfTrainingInfo.class);
                i.putExtra(CrfTrainingInfoKt.EXTRA_HTML_FILENAME, "crf_tips_resting_hr.html");
                i.putExtra(CrfTrainingInfoKt.EXTRA_TITLE, getResources().getString(R.string.crf_tips_for_measuring));
                getContext().startActivity(i);
            }
        }, startIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        calculateSuccess.setText(troubleSpannable);
        calculateSuccess.setMovementMethod(LinkMovementMethod.getInstance());

        if (step.isHrRecoveryStep) {
            nextButton.setOnClickListener(view -> onDoneButtonClicked());
            nextButton.setText(R.string.crf_done);
        } else {
            nextButton.setOnClickListener(view -> onRedoButtonClicked());
            nextButton.setText(R.string.crf_redo);
        }

        findViewById(R.id.crf_error_icon_view).setVisibility(View.VISIBLE);


        heartImageView.clearAnimation();
        heartImageView.setVisibility(View.GONE);
        cameraSourcePreview.setVisibility(View.GONE);
        cameraPreview.setVisibility(View.GONE);
        currentHeartRate.setVisibility(View.GONE);
        crfMessageTextView.setVisibility(View.INVISIBLE);

    }

    protected void showCompleteUi() {
        crfOops.setText(R.string.crf_nicely_done);
        crfOops.setVisibility(View.VISIBLE);
        nextButton.setVisibility(View.VISIBLE);
        heartRateTextContainer.setVisibility(View.VISIBLE);
        calculateSuccess.setVisibility(View.VISIBLE);
        arcDrawableContainer.setVisibility(View.VISIBLE);


        heartImageView.clearAnimation();
        heartImageView.setVisibility(View.GONE);
        cameraSourcePreview.setVisibility(View.GONE);
        cameraPreview.setVisibility(View.GONE);
        currentHeartRate.setVisibility(View.GONE);
        crfMessageTextView.setVisibility(View.INVISIBLE);


        int hrToDisplay = 0;
        if (!bpmList.isEmpty()) {
            if (step.isHrRecoveryStep) {
                hrToDisplay = findLastHr().bpm;
                BpmHolder peakHolder = findPeakHr();
                setBpmResult(peakHolder, PEAK_BPM_VALUE_RESULT, PEAK_BPM_CONFIDENCE_RESULT);
            } else {
                BpmHolder bestHolder = findBestHr();
                hrToDisplay = bestHolder.bpm;
                setBpmResult(bestHolder, RESTING_BPM_VALUE_RESULT, RESTING_CONFIDENCE_VALUE_RESULT);
            }
        }
        heartRateNumber.setText(String.format(Locale.getDefault(), "%d", hrToDisplay));
        finalBpm.setText(String.format(Locale.getDefault(), "%d", hrToDisplay));
    }

    private boolean haveValidHr() {
        if (!bpmList.isEmpty()) {
            for (BpmHolder bpmHolder : bpmList) {
                if (bpmHolder.confidence >= MINIMUM_CONFIDENCE) {
                    return true;
                }
            }
        }
        return false;

    }

    private BpmHolder findBestHr() {
        BpmHolder bestHr = null;
        if (!bpmList.isEmpty()) {
            int bestConfidence = 0;
            for (BpmHolder bpmHolder : bpmList) {
                if (bpmHolder.confidence >= MINIMUM_CONFIDENCE && bpmHolder.confidence > bestConfidence) {
                    bestHr = bpmHolder;
                }
            }
        }
        return bestHr;
    }

    private BpmHolder findLastHr() {
        BpmHolder lastHr = null;
        if (!bpmList.isEmpty()) {
            for (BpmHolder bpmHolder : bpmList) {
                if (bpmHolder.confidence >= MINIMUM_CONFIDENCE) {
                    lastHr = bpmHolder;
                }
            }
        }
        return lastHr;
    }

    private BpmHolder findPeakHr() {
        BpmHolder peakHr = null;
        if (!bpmList.isEmpty()) {
            int peak = 0;
            for (BpmHolder bpmHolder : bpmList) {
                if (bpmHolder.confidence >= MINIMUM_CONFIDENCE && bpmHolder.bpm > peak) {
                    peakHr = bpmHolder;
                }
            }
        }
        return peakHr;
    }

    private void showFinishUi() {
        shouldShowFinishUi = false;
        layout.setBackgroundColor(getResources().getColor(R.color.completion_background_end));
        buttonContainer.setBackgroundColor(getResources().getColor(R.color.white));
        yourHRis.setVisibility(View.VISIBLE);
        finalBpm.setVisibility(View.VISIBLE);
        finalBpmText.setVisibility(View.VISIBLE);

        crfCompletionIcon.setVisibility(View.VISIBLE);
        crfPractice.setVisibility(View.VISIBLE);
        coverFlash.setVisibility(View.VISIBLE);

        crfOops.setVisibility(View.INVISIBLE);
        calculateSuccess.setVisibility(View.INVISIBLE);
        heartContainer.setVisibility(View.GONE);



        nextButton.setText(R.string.crf_done);
        nextButton.setOnClickListener(view -> onNextButtonClicked());

        redoButton.setVisibility(View.VISIBLE);
        redoButton.setOnClickListener(view -> onRedoButtonClicked());

        arcDrawableContainer.setVisibility(View.GONE);

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


    private void setBpmResult(BpmHolder bpm, String bpmIdentifier, String confidenceIdentifier) {
        stepResult.setResultForIdentifier(bpmIdentifier, getBpmStepResult(bpmIdentifier, bpm));

        QuestionStep confQuestion =
                new QuestionStep(confidenceIdentifier, confidenceIdentifier, new DecimalAnswerFormat(0,1));
        StepResult<Double> confResult = new StepResult<>(confQuestion);
        confResult.setResult(bpm.confidence);
        confResult.setStartDate(new Date(bpm.timestamp));
        confResult.setEndDate(new Date(bpm.timestamp));
        stepResult.setResultForIdentifier(confidenceIdentifier, confResult);
    }

    private void setVo2MaxResult(double vo2Max) {
        long roundedVo2Max = Math.round(vo2Max);
        String bpmStepId = CrfHeartRateStepLayout.VO2_MAX_VALUE_RESULT;
        QuestionStep vo2MaxQuestion =
                new QuestionStep(bpmStepId, bpmStepId, new IntegerAnswerFormat(0,100));
        StepResult<Integer> result = new StepResult<>(vo2MaxQuestion);
        result.setResult((int)roundedVo2Max);
        stepResult.setResultForIdentifier(bpmStepId, result);

        long low = roundedVo2Max - 3;
        long high = roundedVo2Max + 3;
        String range = low + " - " + high;
        String bpmRangeStepId = CrfHeartRateStepLayout.VO2_MAX_RANGE_RESULT;
        QuestionStep vo2RangeQuestion =
                new QuestionStep(bpmRangeStepId, bpmRangeStepId, new TextAnswerFormat());
        StepResult<String> rangeResult = new StepResult<>(vo2RangeQuestion);
        rangeResult.setResult(range);
        stepResult.setResultForIdentifier(bpmRangeStepId, rangeResult);

    }

    @Override
    public void intelligentStartUpdate(float progress, boolean ready) {
        if (ready) {
            intelligentStartDetected();
        }
    }

    @Override
    public void crfTaskResult(TaskResult taskResult) {
        if (step.isHrRecoveryStep) {
            sex = StepResultHelper.findStringResult(taskResult, "sex");
            birthYear = StepResultHelper.findIntegerResult("birthYear", taskResult);
        }
    }

    @Override
    public void onComplete(Recorder recorder, Result result) {
        stepResult.setResultForIdentifier(recorder.getIdentifier(), result);

        // don't do this for video recorder, wait for heart rate JSON
        if (recorder instanceof JsonArrayDataRecorder) {
            if (haveValidHr()) {
                //Api target is now 21 so we should always have a HeartRateCamera2Recorder -Nathaniel 4/21/19
                if (step.isHrRecoveryStep && cameraRecorder instanceof HeartRateCamera2Recorder) {
                    int calendarYear = Calendar.getInstance().get(Calendar.YEAR);
                    int age = calendarYear - birthYear;
                    Sex sexEnum = Sex.valueOf(sex);

                    double vo2max = ((HeartRateCamera2Recorder) cameraRecorder).calculateVo2Max(sexEnum, age);
                    if (vo2max > 0) {
                        setVo2MaxResult(vo2max);
                        showCompleteUi();
                        return;
                    }
                } else {
                    showCompleteUi();
                    return;
                }
            }
            showFailureUi();
        }
    }

    @Override
    public void onFail(Recorder recorder, Throwable error) {
        super.showOkAlertDialog(error.getMessage(), (dialogInterface, i) ->
                callbacks.onSaveStep(StepCallbacks.ACTION_END, activeStep, null));
    }

    @Nullable
    @Override
    public Context getBroadcastContext() {
        return getContext().getApplicationContext();
    }

    @Override
    public void pressureUpdate(PressureHolder pressure) {

    }

    @Override
    public void cameraUpdate(CameraCoveredHolder camera) {
        if(!camera.cameraCovered) {
            LOG.warn("Camera isn't covered");
            crfMessageTextView.setText(R.string.crf_move_finger_back);
            crfOops.setVisibility(View.VISIBLE);
            currentHeartRate.setText("--");

            heartImageView.setVisibility(View.INVISIBLE);
            arcDrawableContainer.setVisibility(View.INVISIBLE);
            arcDrawable.setSweepAngle(0.0f);
            cameraPreview.setVisibility(View.VISIBLE);
        } else if (!this.cameraCoverd) {
            //Only update covered state if we haven't already been notified
            crfOops.setVisibility(View.INVISIBLE);
            crfMessageTextView.setText(R.string.crf_camera_cover);
            currentHeartRate.setText(R.string.crf_capturing);

            heartImageView.setVisibility(View.VISIBLE);
            arcDrawableContainer.setVisibility(View.VISIBLE);
            cameraPreview.setVisibility(View.GONE);
        }
        cameraCoverd = camera.cameraCovered;
    }



    @Override
    public void abnormalHRUpdate(AbnormalHRHolder abnormal) {
        if(abnormal.abnormal) {
            StepResult<Boolean> abnormalHRResult = new StepResult<>(new Step("displaySurvey"));
            abnormalHRResult.setResult(false);
            stepResult.setResultForIdentifier("skipAbnormalStep",
                    abnormalHRResult);
        }
        else {
            StepResult<Boolean> abnormalHRResult = new StepResult<>(new Step("displaySurvey"));
            abnormalHRResult.setResult(true);
            stepResult.setResultForIdentifier("skipAbnormalStep",
                    abnormalHRResult);
        }
    }

    @Override
    public void declineHRUpdate(DeclineHRHolder decline) {
        if(decline.declining) {
            StepResult<Boolean> decliningHRResult = new StepResult<>(new Step("displayDecliningHR"));
            decliningHRResult.setResult(false);
            stepResult.setResultForIdentifier("skipDeclineStep",
                    decliningHRResult);
        }
        else {
            StepResult<Boolean> decliningHRResult = new StepResult<>(new Step("displayDecliningHR"));
            decliningHRResult.setResult(true);
            stepResult.setResultForIdentifier("skipDeclineStep",
                    decliningHRResult);
        }
    }

    @Override
    public boolean crfToolbarShowProgress() {
        return false;
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