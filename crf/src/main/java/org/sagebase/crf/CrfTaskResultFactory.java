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

import android.content.Intent;
import androidx.annotation.VisibleForTesting;

import com.google.common.collect.ImmutableList;

import org.sagebionetworks.researchstack.backbone.answerformat.AnswerFormat;
import org.sagebionetworks.researchstack.backbone.result.FileResult;
import org.sagebionetworks.researchstack.backbone.result.StepResult;
import org.sagebionetworks.researchstack.backbone.result.TaskResult;
import org.sagebionetworks.researchstack.backbone.ui.ViewTaskActivity;
import org.sagebase.crf.result.CrfAnswerResult;
import org.sagebase.crf.result.CrfCollectionResult;
import org.sagebase.crf.result.CrfFileResult;
import org.sagebase.crf.result.CrfResult;
import org.sagebase.crf.result.CrfTaskResult;
import org.sagebase.crf.step.body.CrfChoiceAnswerFormat;
import org.sagebase.crf.step.body.CrfIntegerAnswerFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Factory for creating Cardiorespiratory Fitness task results.
 */
public class CrfTaskResultFactory {

    /**
     * @param data data received in onActivityResult for a Cardiorespiratory Fitness task
     * @return task result in Cardiorespiratory Fitness module format
     */
    public static CrfTaskResult create(Intent data) {
        return createFromTaskResult((TaskResult)data.getSerializableExtra(ViewTaskActivity.EXTRA_TASK_RESULT));
    }

    /**
     * @param taskResult raw task result
     * @return task result in Cardiorespiratory Fitness module format
     */
    @VisibleForTesting
    private static CrfTaskResult createFromTaskResult(TaskResult taskResult) {
        List<CrfResult> stepHistory = new ArrayList<>();
        List<CrfResult> asyncResults = new ArrayList<>();

        if (taskResult != null) {
            Map<String, StepResult> stepResults = taskResult.getResults();
            for (StepResult stepResult : stepResults.values()) {
                addResultsRecursively(stepResult, stepHistory, asyncResults);
            }
        }

        String identifier = taskResult.getIdentifier();

        CrfTaskResult crfTaskResult = new CrfTaskResult(identifier, taskResult.getStartDate(), taskResult.getEndDate(), ImmutableList.copyOf(stepHistory), ImmutableList.copyOf(asyncResults));
        return crfTaskResult;
    }


    private CrfTaskResultFactory() {
    }


    private static boolean addResultsRecursively(StepResult stepResult, List<CrfResult> resultList, List<CrfResult> asyncResults) {
        boolean wentDeeper = false;
        List<CrfResult> resultListToAdd = resultList;

        if (stepResult != null) {
            Map<String, Object> stepResultMap = stepResult.getResults();

            if (stepResultMap.size() > 1) {
                //Create a list that will become part of of CrfCollectionResult
                List<CrfResult> collectionResultList = new ArrayList<>();
                resultListToAdd = collectionResultList;
            }

            for (String key : stepResultMap.keySet()) {
                Object value = stepResultMap.get(key);

                // The StepResult is a special case, because it could contain nested StepResults,
                // or it could contain FileResults, which need added themselves,
                // while the StepResult still needs added too if it isn't nested
                if (value instanceof StepResult) {
                    wentDeeper = true;

                    StepResult nestedStepResult = (StepResult) value;
                    if (!nestedStepResult.getResults().isEmpty()) {
                        addResultsRecursively((StepResult) value, resultListToAdd, asyncResults);
                    }
                } else if (value instanceof FileResult) {
                    FileResult fileResult = (FileResult) value;
                    CrfFileResult crfResult = new CrfFileResult(key,
                            fileResult.getStartDate(),
                            fileResult.getEndDate(),
                            fileResult.getContentType(),
                            fileResult.getFile().getPath());
                    asyncResults.add(crfResult);
                }
            }
            if (stepResultMap.size() > 1) {
                CrfCollectionResult crfResult = new CrfCollectionResult(stepResult.getIdentifier(),
                        stepResult.getStartDate(),
                        stepResult.getEndDate(),
                        ImmutableList.copyOf(resultListToAdd));
                resultList.add(crfResult);
            }
        }

        if (!wentDeeper) {
            AnswerFormat answerFormat = stepResult.getAnswerFormat();
            //Only add steps that have an answer format
            if (answerFormat != null) {
                String answerType = answerFormat.getQuestionType().toString();
                //The QuestionTypes for the Crf answer formats don't aren't Enums, so toString doesn't work right.
                if (answerFormat instanceof CrfIntegerAnswerFormat) {
                    answerType = AnswerFormat.Type.Integer.name();
                } else if (answerFormat instanceof CrfChoiceAnswerFormat) {
                    answerType = AnswerFormat.Type.SingleChoice.name();
                }

                CrfResult crfResult = new CrfAnswerResult(stepResult.getIdentifier(),
                        stepResult.getStartDate(),
                        stepResult.getEndDate(),
                        stepResult.getResult(),
                        answerType);
                resultListToAdd.add(crfResult);
            }
        }

        return wentDeeper;
    }
}
