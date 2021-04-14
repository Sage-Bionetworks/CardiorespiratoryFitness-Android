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

package org.sagebase.crf;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.ColorRes;
import androidx.annotation.IdRes;
import androidx.core.content.res.ResourcesCompat;
import androidx.appcompat.app.ActionBar;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import org.sagebionetworks.researchstack.backbone.PermissionRequestManager;
import org.sagebionetworks.researchstack.backbone.step.Step;
import org.sagebionetworks.researchstack.backbone.task.OrderedTask;
import org.sagebionetworks.researchstack.backbone.task.Task;
import org.sagebionetworks.researchstack.backbone.ui.ActiveTaskActivity;
import org.sagebionetworks.researchstack.backbone.ui.step.layout.ActiveStepLayout;
import org.sagebionetworks.researchstack.backbone.ui.step.layout.StepLayout;
import org.sagebase.crf.step.CrfFormStep;
import org.sagebase.crf.step.CrfResultListener;
import org.sagebase.crf.view.CrfTaskStatusBarManipulator;
import org.sagebase.crf.view.CrfTaskToolbarActionManipulator;
import org.sagebase.crf.view.CrfTaskToolbarIconManipulator;
import org.sagebase.crf.view.CrfTaskToolbarProgressManipulator;
import org.sagebase.crf.view.CrfTaskToolbarTintManipulator;
import org.sagebase.crf.view.CrfTaskToolbarVisibilityManipulator;
import org.sagebase.crf.view.CrfTransparentToolbar;
import org.sagebase.crf.view.ViewUtils;

import java.util.List;

/**
 * Created by TheMDP on 10/24/17.
 */

public class CrfActiveTaskActivity extends ActiveTaskActivity {

    public static Intent newIntent(Context context, Task task) {
        Intent intent = new Intent(context, CrfActiveTaskActivity.class);
        intent.putExtra(EXTRA_TASK, task);
        return intent;
    }

    protected CrfTransparentToolbar getToolbar() {
        if (toolbar != null && toolbar instanceof CrfTransparentToolbar) {
            return (CrfTransparentToolbar)toolbar;
        }
        return null;
    }

    private TextView crfStepProgressTextview;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        boolean status = super.onCreateOptionsMenu(menu);
        refreshToolbar();
        return status;
    }

    @Override
    public void onDataAuth() {
        storageAccessUnregister();
        super.onDataReady();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        crfStepProgressTextview = findViewById(R.id.crf_step_progress_textview);
    }

    @Override
    public int getContentViewId() {
        return R.layout.activity_active_task;
    }

    public int getViewSwitcherRootId() {
        return R.id.crf_active_container;
    }

    @Override
    public @IdRes int getToolbarResourceId() {
        return R.id.crf_task_toolbar;
    }

    @Override
    public void showStep(Step step, boolean alwaysReplaceView) {
        super.showStep(step, alwaysReplaceView);
        refreshToolbar();

        // Let steps know about the task result if it needs to
        if (getCurrentStepLayout() instanceof CrfResultListener) {
            ((CrfResultListener)getCurrentStepLayout()).crfTaskResult(taskResult);
        }
    }

    public void refreshToolbar() {
        if (getCurrentStepLayout() == null) {
            return;
        }

        CrfTransparentToolbar crfToolbar = getToolbar();
        StepLayout current = getCurrentStepLayout();
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        // Allow for customization of the toolbar
        crfToolbar.refreshToolbar(
                actionBar,      // used to set icons
                current,        // the object that may inherit from a manipulator
                defaultToolbarTintColor(),
                R.drawable.ic_close_white_24dp,
                CrfTaskToolbarIconManipulator.NO_ICON);

        // The text color of the step progress defaults to white,
        // but is set to a darker theme for all tint colors other than white
        @ColorRes int stepProgressTextColorRes = defaultStepProgressColor();
        if (current instanceof CrfTaskToolbarTintManipulator) {
            if (((CrfTaskToolbarTintManipulator)current).crfToolbarTintColor() != R.color.white) {
                stepProgressTextColorRes = R.color.textColor;
            }
        }
        int stepProgressTextColor = ResourcesCompat.getColor(getResources(), stepProgressTextColorRes, null);
        crfStepProgressTextview.setTextColor(stepProgressTextColor);

        // Set the visibility of the step progress text to mimic the progress bar visibility
        if (!(current instanceof CrfTaskToolbarProgressManipulator)) {
            crfToolbar.showProgressInToolbar(true);
            crfStepProgressTextview.setVisibility(View.GONE);
        } else if (((CrfTaskToolbarProgressManipulator)current).crfToolbarShowProgress()) {
            crfStepProgressTextview.setVisibility(View.GONE);
        } else {
            crfStepProgressTextview.setVisibility(View.GONE);
        }

        // Allow for customization of the status bar
        @ColorRes int statusBarColor = R.color.colorPrimaryDark;
        if (current instanceof CrfTaskStatusBarManipulator) {
            CrfTaskStatusBarManipulator manipulator = (CrfTaskStatusBarManipulator)current;
            if (manipulator.crfStatusBarColor() != CrfTaskStatusBarManipulator.DEFAULT_COLOR) {
                statusBarColor = manipulator.crfStatusBarColor();
            }
        }
        int color = ResourcesCompat.getColor(getResources(), statusBarColor, null);
        ViewUtils.setStatusBarColor(this, color);

        // Set the step progress
        if (task instanceof OrderedTask) {
            OrderedTask orderedTask = (OrderedTask)task;

            
            int progress = orderedTask.getSteps().indexOf(currentStep);

            List<Step> steps = orderedTask.getSteps();
            int max = 1;
            for(Step s: steps) {
                if (s instanceof CrfFormStep) {
                    //Currently the only steps that show progress are the stair step survey questions
                    //This is the quick solution to only show progress relative to those steps
                    if (s == currentStep) {
                        progress = max;
                    }
                    max += 1;
                }
            }
            crfToolbar.setProgress(progress, max);

            // Set up the text and styling of the step 1 of 5, 2 of 5, etc.
            String progressStr = String.valueOf(progress + 1); // array index 0 should be 1
            String maxString = String.valueOf(max);
            String stepProgressStr = String.format(getString(R.string.crf_step_progress), progressStr, maxString);

            SpannableStringBuilder str = new SpannableStringBuilder(stepProgressStr);
            str.setSpan(new StyleSpan(android.graphics.Typeface.BOLD),
                    stepProgressStr.indexOf(progressStr),
                    stepProgressStr.indexOf(progressStr) + progressStr.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

            crfStepProgressTextview.setText(str);
        } else {
            Log.e("CrfActiveTaskActivity", "Progress Bars only work with OrderedTask");
        }

        if (current instanceof CrfTaskToolbarVisibilityManipulator) {
            CrfTaskToolbarVisibilityManipulator manipulator = (CrfTaskToolbarVisibilityManipulator) current;
            crfToolbar.setVisibility(manipulator.crfToolbarHide() ? View.GONE : View.VISIBLE);
        } else {
            crfToolbar.setVisibility(View.VISIBLE);
        }

        // Media Volume controls
        int streamType = AudioManager.USE_DEFAULT_STREAM_TYPE;
        if (current instanceof CrfTaskMediaVolumeController) {
            if (((CrfTaskMediaVolumeController)current).controlMediaVolume()) {
                streamType = AudioManager.STREAM_MUSIC;
            }
        } else if (current instanceof ActiveStepLayout) {
            // ActiveStepLayouts have verbal spoken instructions
            streamType = AudioManager.STREAM_MUSIC;
        }
        setVolumeControlStream(streamType);
    }

    protected @ColorRes int defaultToolbarTintColor() {
        return R.color.white;
    }

    protected @ColorRes int defaultStepProgressColor() {
        return R.color.white;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        StepLayout current = getCurrentStepLayout();
        // Allow for customization of the toolbar
        if (current instanceof CrfTaskToolbarActionManipulator) {
            CrfTaskToolbarActionManipulator manipulator = (CrfTaskToolbarActionManipulator) current;
            if(item.getItemId() == org.sagebionetworks.researchstack.backbone.R.id.rsb_clear_menu_item) {
                return manipulator.crfToolbarRightIconClicked();
            }
        } else if(item.getItemId() == android.R.id.home) {
            showConfirmExitDialog();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void notifyStepOfBackPress() {
//        if (getCurrentStep() instanceof CrfStartTaskStep) {
//            super.notifyStepOfBackPress();
//        }
    }

    public interface CrfTaskMediaVolumeController {
        /**
         * @return if true, volume buttons will control media, not ringer
         */
        boolean controlMediaVolume();
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(resultCode == RESULT_OK) {
            StepLayout layout = getCurrentStepLayout();
            if(layout instanceof CrfActivityResultListener) {
                ((CrfActivityResultListener)layout).onActivityFinished(requestCode, resultCode, data);
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }


    @TargetApi(Build.VERSION_CODES.M)
    @Override //Override so we don't need to initialize the PermissionRequestManager
    public void onRequestPermission(String id) {
        requestPermissions(new String[] {id}, PermissionRequestManager.PERMISSION_REQUEST_CODE);
    }


    @Override
    protected void requestStorageAccess() {
        onDataReady();
    }

    @Override
    protected void storageAccessRegister() {
    }

    @Override
    protected void storageAccessUnregister() {
    }

}
