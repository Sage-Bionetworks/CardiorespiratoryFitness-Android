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
import android.support.annotation.VisibleForTesting;
import android.widget.Toast;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import org.researchstack.backbone.DataProvider;
import org.researchstack.backbone.model.SchedulesAndTasksModel;
import org.researchstack.backbone.task.Task;
import org.researchstack.skin.ui.fragment.ActivitiesFragment;

import org.sagebionetworks.bridge.researchstack.CrfDataProvider;
import org.sagebionetworks.bridge.researchstack.CrfTaskFactory;
import org.sagebionetworks.bridge.rest.model.ScheduledActivity;
import org.sagebionetworks.bridge.rest.model.ScheduledActivityListV4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.Set;

/**
 * Created by TheMDP on 10/19/17
 */
public class CrfActivitiesFragment extends ActivitiesFragment {
    // Task IDs that should be hidden from the activities page. Visible to enable unit tests.
    @VisibleForTesting
    static final Set<String> HIDDEN_TASK_IDS = ImmutableSet.of(CrfDataProvider.CLINIC1,
            CrfDataProvider.CLINIC2);

    private static final String LOG_TAG = CrfActivitiesFragment.class.getCanonicalName();

    /**
     * When true, we will use the base class' fetch activities
     * When false, we will use the new clinic assignment groupings of activities
     */
    private static final boolean USE_LEGACY_GET_ACTIVITIES = true;

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

    // Mapping from task ID to resource name. Visible to enable unit tests.
    @VisibleForTesting
    static final Map<String, String> TASK_ID_TO_RESOURCE_NAME =
            ImmutableMap.<String, String>builder()
                    .put(CrfTaskFactory.TASK_ID_HEART_RATE_MEASUREMENT, "heart_rate_measurement")
                    .put(CrfTaskFactory.TASK_ID_CARDIO_12MT, "12_minute_walk")
                    .put(CrfTaskFactory.TASK_ID_STAIR_STEP, "stair_step")
                    .build();

    private CrfTaskFactory taskFactory = new CrfTaskFactory();

    // To allow unit tests to mock.
    @VisibleForTesting
    void setTaskFactory(CrfTaskFactory taskFactory) {
        this.taskFactory = taskFactory;
    }

    @Override
    protected void startCustomTask(SchedulesAndTasksModel.TaskScheduleModel task) {
        if (TASK_ID_TO_RESOURCE_NAME.containsKey(task.taskID)) {
            Task testTask = taskFactory.createTask(getActivity(), TASK_ID_TO_RESOURCE_NAME.get(task
                    .taskID));
            startActivityForResult(getIntentFactory().newTaskIntent(getActivity(),
                    CrfActiveTaskActivity.class, testTask), REQUEST_TASK);
        } else {
            Toast.makeText(getActivity(),
                    org.researchstack.skin.R.string.rss_local_error_load_task,
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Legacy way of processing results, it will just show whatever is handed to it
     * @param model SchedulesAndTasksModel object
     * @return a list of TaskScheduleModel
     */
    @Override
    public List<Object> processResults(SchedulesAndTasksModel model) {
        if (model == null || model.schedules == null) {
            return new ArrayList<>();
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
