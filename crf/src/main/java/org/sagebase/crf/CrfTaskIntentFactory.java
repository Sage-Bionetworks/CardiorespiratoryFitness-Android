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

import android.content.Context;
import android.content.Intent;

import org.sagebionetworks.researchstack.backbone.factory.IntentFactory;
import org.sagebase.crf.researchstack.CrfResourceManager;
import org.sagebionetworks.researchstack.backbone.result.StepResult;
import org.sagebionetworks.researchstack.backbone.result.TaskResult;
import org.sagebionetworks.researchstack.backbone.step.Step;

import static org.sagebase.crf.researchstack.CrfTaskFactory.TASK_ID_HEART_SNAPSHOT;

/**
 * Creates Intents for launching Cardiorespiratory Fitness Module tasks.
 */
public class CrfTaskIntentFactory {
    private static final org.sagebase.crf.researchstack.CrfTaskFactory taskFactory =
            new org.sagebase.crf.researchstack.CrfTaskFactory();

    private static final IntentFactory intentFactory = IntentFactory.INSTANCE;

    /**
     * These need to match the identifiers in the Heart Snapshot JSON
     */
    public static final String genderResultIdentifier = "sex";
    public static final String genderFormResultIdentifier = genderResultIdentifier + "Form";
    public static final String birthYearResultIdentifier = "birthYear";
    public static final String birthYearFormResultIdentifier = birthYearResultIdentifier + "Form";

    /**
     * @param context application context
     * @return Intent for launching Heart Rate Training activity
     */
    public static Intent getHeartRateTrainingTaskIntent(Context context) {
        return intentFactory.newTaskIntent(
                context,
                CrfActiveTaskActivity.class,
                taskFactory.createTask(
                        context,
                        CrfResourceManager.HEART_RATE_TRAINING_TEST_RESOURCE));
    }

    /**
     * @param context application context
     * @return Intent for launching Heart Rate Measurement activity
     */
    public static Intent getHeartRateMeasurementTaskIntent(Context context) {
        return intentFactory.newTaskIntent(
                context,
                CrfActiveTaskActivity.class,
                taskFactory.createTask(
                        context,
                        CrfResourceManager.HEART_RATE_MEASUREMENT_TEST_RESOURCE));
    }

    /**
     * @param context application context
     * @return Intent for launching Heart Snapshot activity
     */
    public static Intent getHeartRateSnapshotTaskIntent(Context context) {
        return intentFactory.newTaskIntent(
                context,
                CrfActiveTaskActivity.class,
                taskFactory.createTask(
                        context,
                        CrfResourceManager.HEART_RATE_SNAPSHOT_RESOURCE));
    }
    /**
     * @param context application context
     * @param gender the previous answer to the gender question, so those questions are skipped
     * @@param birthYear the previous answer to the gender question, so those questions are skipped
     * @return Intent for launching Heart Snapshot activity
     */
    public static Intent getHeartRateSnapshotTaskIntent(Context context,
                                                        String gender, int birthYear) {

        TaskResult taskResult = new TaskResult(TASK_ID_HEART_SNAPSHOT);

        StepResult<String> genderResult = new StepResult<>(new Step(genderResultIdentifier));
        genderResult.setResult(gender);
        taskResult.setStepResultForStepIdentifier(genderResultIdentifier, genderResult);

        StepResult<Integer> birthYearResult = new StepResult<>(new Step(birthYearResultIdentifier));
        birthYearResult.setResult(birthYear);
        taskResult.setStepResultForStepIdentifier(birthYearResultIdentifier, birthYearResult);

        return intentFactory.newTaskIntent(
                context,
                CrfActiveTaskActivity.class,
                taskFactory.createTask(
                        context,
                        CrfResourceManager.HEART_RATE_SNAPSHOT_RESOURCE),
                taskResult);
    }


    private CrfTaskIntentFactory() {
    }
}
