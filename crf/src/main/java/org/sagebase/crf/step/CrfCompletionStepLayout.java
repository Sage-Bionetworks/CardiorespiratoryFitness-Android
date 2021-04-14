/*
 *    Copyright 2019 Sage Bionetworks
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
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.sagebionetworks.researchstack.backbone.result.StepResult;
import org.sagebionetworks.researchstack.backbone.result.TaskResult;
import org.sagebionetworks.researchstack.backbone.step.Step;
import org.sagebionetworks.researchstack.backbone.ui.callbacks.StepCallbacks;
import org.sagebionetworks.researchstack.backbone.utils.StepResultHelper;
import org.sagebase.crf.R;
import org.sagebase.crf.view.CrfTaskStatusBarManipulator;
import org.sagebase.crf.view.CrfTaskToolbarVisibilityManipulator;

/**
 * Created by TheMDP on 11/5/17.
 */

public class CrfCompletionStepLayout extends CrfInstructionStepLayout implements CrfResultListener,
        CrfTaskStatusBarManipulator,
        CrfTaskToolbarVisibilityManipulator {

    public static final String COMPLETION_DISTANCE_VALUE_RESULT = "completion_distance_result";

    private View mCompletionTextContainer;
    private TextView mCompletionTextTop;
    private TextView mCompletionValueText;
    private TextView mSecondaryCompletionValueText;
    private TextView mCompletionLabelText;
    private TextView mCompletionTextBottom;

    private Button mRedoButton;

    // This is passed in from the TaskResult
    private String mCompletionValueResult;
    private String mSecondaryCompletionValueResult;

    private CrfCompletionStep crfCompletionStep;

    public CrfCompletionStepLayout(Context context) {
        super(context);
    }

    public CrfCompletionStepLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CrfCompletionStepLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public CrfCompletionStepLayout(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public int getContentResourceId() {
        return R.layout.crf_step_layout_completion;
    }

    @Override
    public void initialize(Step step, StepResult result) {
        validateAndSetCrfCompletionStep(step);
        super.initialize(step, result);
    }

    protected void validateAndSetCrfCompletionStep(Step step) {
        if (!(step instanceof CrfCompletionStep)) {
            throw new IllegalStateException("CrfCompletionStepLayout only works with CrfCompletionStep");
        }
        this.crfCompletionStep = (CrfCompletionStep)step;
    }

    @Override
    public void refreshStep() {
        super.refreshStep();

        mCompletionTextContainer = findViewById(R.id.crf_text_container);

        mCompletionTextTop = findViewById(R.id.crf_completion_text_top);
        mCompletionTextTop.setText(crfCompletionStep.topText);
        mCompletionTextTop.setVisibility((crfCompletionStep.topText == null) ? View.GONE : View.VISIBLE);

        mCompletionLabelText = findViewById(R.id.crf_completion_text_label);
        mCompletionLabelText.setText(crfCompletionStep.valueLabelText);
        mCompletionLabelText.setVisibility((crfCompletionStep.valueLabelText == null) ? View.GONE : View.VISIBLE);

//        mCompletionTextBottom = findViewById(R.id.crf_completion_text_bottom);
//        mCompletionTextBottom.setText(crfCompletionStep.bottomText);
//        mCompletionTextBottom.setVisibility((crfCompletionStep.bottomText == null) ? View.GONE : View.VISIBLE);

        mCompletionValueText = findViewById(R.id.crf_completion_text_value);
        mSecondaryCompletionValueText = findViewById(R.id.crf_secondary_completion_text_value);
        refreshCompletionValueLabel();

        mRedoButton = findViewById(R.id.crf_redo_button);
        if (crfCompletionStep.showRedoButton) {
            mRedoButton.setVisibility(View.VISIBLE);
            mRedoButton.setOnClickListener(view -> onRedoButtonClicked());
        } else {
            mRedoButton.setVisibility(View.GONE);
        }
    }

    @Override
    public void crfTaskResult(TaskResult taskResult) {
        StepResult stepResult = StepResultHelper.findStepResult(taskResult, crfCompletionStep.valueResultId);
        mCompletionValueResult = String.valueOf(stepResult.getResult());
        StepResult stepResultSecondary = StepResultHelper.findStepResult(taskResult, crfCompletionStep.secondaryValueResultId);
        if (stepResultSecondary != null) {
            mSecondaryCompletionValueResult = String.valueOf(stepResultSecondary.getResult());
        }


        refreshCompletionValueLabel();
    }

    private void refreshCompletionValueLabel() {
        if (mCompletionValueText == null || mCompletionLabelText == null || mCompletionValueResult == null) {
            mCompletionTextContainer.setVisibility(View.GONE);
            return;
        }
        mCompletionTextContainer.setVisibility(View.VISIBLE);
        mCompletionValueText.setText(mCompletionValueResult);
        if (mSecondaryCompletionValueResult == null) {
            mSecondaryCompletionValueText.setVisibility(View.GONE);
        } else {
            mSecondaryCompletionValueText.setVisibility(View.VISIBLE);
            mSecondaryCompletionValueText.setText(mSecondaryCompletionValueResult);
        }
    }

    public void onRedoButtonClicked() {
        callbacks.onSaveStep(StepCallbacks.ACTION_PREV, step, null);
    }

    @Override
    public boolean crfToolbarHide() {
        return true;
    }

    @Override
    public int crfStatusBarColor() {
        return R.color.completion_background_end;
    }
}
