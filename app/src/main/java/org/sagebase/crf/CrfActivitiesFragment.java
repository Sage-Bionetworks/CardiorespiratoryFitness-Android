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

package org.sagebase.crf;

import android.app.AlertDialog;
import android.support.annotation.NonNull;
import android.util.Log;
import android.widget.Toast;

import com.google.common.collect.Lists;

import org.researchstack.backbone.DataProvider;
import org.researchstack.backbone.model.SchedulesAndTasksModel;
import org.researchstack.backbone.step.InstructionStep;
import org.researchstack.backbone.step.PermissionsStep;
import org.researchstack.backbone.step.Step;
import org.researchstack.backbone.task.OrderedTask;
import org.researchstack.backbone.task.Task;
import org.researchstack.backbone.ui.ActiveTaskActivity;
import org.researchstack.backbone.ui.ViewTaskActivity;
import org.researchstack.backbone.utils.LogExt;
import org.researchstack.backbone.utils.ObservableUtils;
import org.researchstack.backbone.utils.StepLayoutHelper;
import org.researchstack.skin.ui.fragment.ActivitiesFragment;
import org.sagebase.crf.step.CrfHeartRateCameraStep;
import org.sagebionetworks.bridge.researchstack.CrfDataProvider;
import org.sagebionetworks.bridge.researchstack.CrfTaskFactory;
import org.sagebionetworks.bridge.rest.model.ScheduledActivity;
import org.sagebionetworks.bridge.rest.model.ScheduledActivityList;
import org.sagebionetworks.bridge.rest.model.ScheduledActivityListV4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import rx.Observable;

/**
 * Created by TheMDP on 10/19/17
 */

public class CrfActivitiesFragment extends ActivitiesFragment {

    private static final String LOG_TAG = CrfActivitiesFragment.class.getCanonicalName();

    private static final boolean USE_LEGACY_GET_ACTIVITIES = false;
    //private static final int DEBUG_BUILD_ACTIVITY_SUBARRAY_INDEX = -1;
    private static final int DEBUG_BUILD_ACTIVITY_SUBARRAY_INDEX = 5;

    @Override
    public void fetchData() {
        getSwipeFreshLayout().setRefreshing(true);

        if (USE_LEGACY_GET_ACTIVITIES) {
            super.fetchData();
            return;
        }

        if (!(DataProvider.getInstance() instanceof  CrfDataProvider)) {
            throw new IllegalStateException("Special activities algorithm only available with CrfDataProvider");
        }

        CrfDataProvider crfDataProvider = (CrfDataProvider)DataProvider.getInstance();
        crfDataProvider.getCrfActivities(new CrfDataProvider.CrfActivitiesListener() {
            @Override
            public void success(ScheduledActivityListV4 activityList) {
                getSwipeFreshLayout().setRefreshing(false);
                if (getAdapter() == null) {
                    unsubscribe();
                    setAdapter(createTaskAdapter());
                    getRecyclerView().setAdapter(getAdapter());

                    setRxSubscription(getAdapter().getPublishSubject().subscribe(task -> {
                        taskSelected(task);
                    }));
                } else {
                    getAdapter().clear();
                }


                List<ScheduledActivity> scheduledActivities = processResults(activityList);
                Log.d(LOG_TAG, scheduledActivities.toString());

                SchedulesAndTasksModel model = translateActivities(scheduledActivities);
            }

            @Override
            public void error(String localizedError) {
                getSwipeFreshLayout().setRefreshing(false);
                new AlertDialog.Builder(getContext()).setMessage(localizedError).create().show();
            }
        });
    }

    @Override
    public void taskSelected(SchedulesAndTasksModel.TaskScheduleModel task) {
        Task newTask = DataProvider.getInstance().loadTask(getContext(), task);
        if (newTask == null) {

            if (task.taskID.equals(CrfTaskFactory.TASK_ID_CARDIO_12MT)) {
                CrfTaskFactory taskFactory = new CrfTaskFactory();
                Task testTask = taskFactory.createTask(getActivity(), "12_minute_walk");
                startActivity(CrfActiveTaskActivity.newIntent(getActivity(), testTask));
            } else if (task.taskID.equals(CrfTaskFactory.TASK_ID_STAIR_STEP)) {
                CrfTaskFactory taskFactory = new CrfTaskFactory();
                Task testTask = taskFactory.createTask(getActivity(), "stair_step");
                startActivity(CrfActiveTaskActivity.newIntent(getActivity(), testTask));
            } else if (task.taskID.equals(CrfTaskFactory.TASK_ID_HEART_RATE_MEASUREMENT)) {
                CrfTaskFactory taskFactory = new CrfTaskFactory();
                Task testTask = taskFactory.createTask(getActivity(), "heart_rate_measurement");
                startActivity(CrfActiveTaskActivity.newIntent(getActivity(), testTask));
            } else {
                Toast.makeText(getActivity(),
                        org.researchstack.skin.R.string.rss_local_error_load_task,
                        Toast.LENGTH_SHORT).show();
            }

            return;
        }

        startActivityForResult(ViewTaskActivity.newIntent(getContext(), newTask), REQUEST_TASK);
    }

    /**
     * Legacy way of processing results, it will just show whatever is handed to it
     * @param model SchedulesAndTasksModel object
     * @return a list of TaskScheduleModel
     */
    @Override
    public List<Object> processResults(SchedulesAndTasksModel model) {
        if (model == null || model.schedules == null) {
            return Lists.newArrayList();
        }
        List<Object> tasks = new ArrayList<>();

        for (SchedulesAndTasksModel.ScheduleModel scheduleModel : model.schedules) {
            for (SchedulesAndTasksModel.TaskScheduleModel task : scheduleModel.tasks) {
                if (task.taskID != null && !hiddenActivityIdentifiers().contains(task.taskID)) {
                    tasks.add(task);
                }
            }
        }

        return tasks;
    }

    /**
     * TODO: Rian this is where you can access the data model
     */
    public List<ScheduledActivity> processResults(ScheduledActivityListV4 activityList) {
        if (activityList == null || activityList.getItems() == null) {
            return Lists.newArrayList();
        }
        List<ScheduledActivity> activities = new ArrayList<>(activityList.getItems());

        if (DEBUG_BUILD_ACTIVITY_SUBARRAY_INDEX > 0) {
            activities = activities.subList(DEBUG_BUILD_ACTIVITY_SUBARRAY_INDEX, activities.size());
            // Also, the 4th to last activity was a mistake, remove it
            activities.remove(activities.size() - 4);
        }

        List<ScheduledActivity> finalActivities = new ArrayList<>();
        // For now, the filter is only on whatever identifiers are in hiddenActivityIdentifiers()
        for (ScheduledActivity activity : activities) {
            if (activity.getActivity() != null &&
                    activity.getActivity().getTask() != null &&
                    activity.getActivity().getTask().getIdentifier() != null) {
                if (!hiddenActivityIdentifiers().contains(activity.getActivity().getTask().getIdentifier())) {
                    finalActivities.add(activity);
                }
            } else {
                finalActivities.add(activity);
            }
        }

        return activities;
    }

    private SchedulesAndTasksModel translateActivities(@NonNull List<ScheduledActivity> activityList) {
        // first, group activities by day
        Map<Integer, List<ScheduledActivity>> activityMap = new HashMap<>();
        for (ScheduledActivity sa : activityList) {
            int day = sa.getScheduledOn().dayOfYear().get();
            List<ScheduledActivity> actList = activityMap.get(day);
            if (actList == null) {
                actList = new ArrayList<>();
                actList.add(sa);
                activityMap.put(day, actList);
            } else {
                actList.add(sa);
            }
        }

        SchedulesAndTasksModel model = new SchedulesAndTasksModel();
        model.schedules = new ArrayList<>();
        for (int day : activityMap.keySet()) {
            List<ScheduledActivity> aList = activityMap.get(day);
            ScheduledActivity temp = aList.get(0);

            SchedulesAndTasksModel.ScheduleModel sm = new SchedulesAndTasksModel.ScheduleModel();
            sm.scheduleType = "once";
            sm.scheduledOn = temp.getScheduledOn().toLocalDate().toDate();
            model.schedules.add(sm);
            sm.tasks = new ArrayList<>();

            for (ScheduledActivity sa : aList) {
                SchedulesAndTasksModel.TaskScheduleModel tsm = new SchedulesAndTasksModel
                        .TaskScheduleModel();
                tsm.taskTitle = sa.getActivity().getLabel();
                tsm.taskCompletionTime = sa.getActivity().getLabelDetail();
                if (sa.getActivity().getTask() != null) {
                    tsm.taskID = sa.getActivity().getTask().getIdentifier();
                }
                tsm.taskIsOptional = sa.getPersistent();
                tsm.taskType = sa.getActivity().getType();
                sm.tasks.add(tsm);
            }
        }

        return model;
    }

    public List<String> hiddenActivityIdentifiers() {
        String [] hideTheseActivities = new String [] {
                CrfDataProvider.CLINIC1,
                CrfDataProvider.CLINIC2};

        return new ArrayList<>(Arrays.asList(hideTheseActivities));
    }
}
