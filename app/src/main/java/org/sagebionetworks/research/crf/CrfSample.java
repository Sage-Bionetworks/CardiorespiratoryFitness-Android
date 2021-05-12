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

package org.sagebionetworks.research.crf;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.sagebase.crf.CrfTaskIntentFactory;
import org.sagebase.crf.CrfTaskResultFactory;
import org.sagebase.crf.result.CrfTaskResult;
import org.sagebionetworks.researchstack.backbone.result.StepResult;
import org.sagebionetworks.researchstack.backbone.result.TaskResult;
import org.sagebionetworks.researchstack.backbone.ui.ViewTaskActivity;
import org.sagebionetworks.researchstack.backbone.utils.StepResultHelper;

public class CrfSample extends AppCompatActivity {

    private static final int CRF_TASK_REQUEST_CODE = 1492;
    private static Integer birthYear = null;
    private static String gender = null;

    private static final String trainingTaskTitle = "Heart Rate Training";
    private static final String restingHrTaskTitle = "Resting Heart Rate";
    private static final String snapshotTaskTitle = "Heart Snapshot";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_crf_sample);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        LinearLayout taskContainer = findViewById(R.id.crf_task_container);

        addTask(taskContainer, trainingTaskTitle);
        addTask(taskContainer, restingHrTaskTitle);
        addTask(taskContainer, snapshotTaskTitle);
    }

    private void addTask(final ViewGroup taskContainer, final String taskTitle) {
        View taskView = LayoutInflater.from(this).inflate(R.layout.crf_task, taskContainer, false);
        taskContainer.addView(taskView);
        Button taskButton = taskView.findViewById(R.id.button_start_task);
        TextView taskTitleTextView = taskView.findViewById(R.id.task_name);
        taskTitleTextView.setText(taskTitle);
        taskButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startTask(taskTitle);
            }
        });
    }

    private void startTask(final String taskTitle) {

        Intent taskIntent = null;
        switch (taskTitle) {
            case trainingTaskTitle:
                taskIntent =  CrfTaskIntentFactory.getHeartRateTrainingTaskIntent(this);
                break;
            case restingHrTaskTitle:
                taskIntent = CrfTaskIntentFactory.getHeartRateMeasurementTaskIntent(this);
                break;
            case snapshotTaskTitle:
                if (gender != null && birthYear != null) {
                    taskIntent = CrfTaskIntentFactory
                            .getHeartRateSnapshotTaskIntent(this, gender, birthYear);
                } else {
                    taskIntent = CrfTaskIntentFactory.getHeartRateSnapshotTaskIntent(this);
                }
                break;
        }

        if (taskIntent != null) {
            startActivityForResult(taskIntent, CRF_TASK_REQUEST_CODE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK && requestCode == CRF_TASK_REQUEST_CODE) {

            TaskResult taskResult =
                    (TaskResult)data.getSerializableExtra(ViewTaskActivity.EXTRA_TASK_RESULT);

            gender = StepResultHelper.findStringResult(taskResult,
                    CrfTaskIntentFactory.genderResultIdentifier);
            birthYear = StepResultHelper.findIntegerResult(
                    CrfTaskIntentFactory.birthYearResultIdentifier, taskResult);

            CrfTaskResult crfTaskResult = CrfTaskResultFactory.create(data);
            Log.d("CrfSample", String.valueOf(crfTaskResult));
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }
}
