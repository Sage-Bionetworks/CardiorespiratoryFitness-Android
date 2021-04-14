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

package org.sagebionetworks.bridge.researchstack;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.preference.PreferenceManager;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import org.joda.time.DateTime;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.sagebionetworks.researchstack.backbone.AppPrefs;
import org.sagebionetworks.researchstack.backbone.DataResponse;
import org.sagebionetworks.researchstack.backbone.model.SchedulesAndTasksModel;
import org.sagebionetworks.researchstack.backbone.result.TaskResult;
import org.sagebionetworks.researchstack.backbone.storage.file.FileAccess;
import org.sagebionetworks.researchstack.backbone.storage.file.PinCodeConfig;
import org.sagebionetworks.researchstack.backbone.ui.ActiveTaskActivity;
import org.sagebionetworks.bridge.android.BridgeConfig;
import org.sagebionetworks.bridge.android.manager.ActivityManager;
import org.sagebionetworks.bridge.android.manager.AuthenticationManager;
import org.sagebionetworks.bridge.android.manager.BridgeManagerProvider;
import org.sagebionetworks.bridge.android.manager.dao.AccountDAO;
import org.sagebionetworks.bridge.android.manager.dao.ConsentDAO;
import org.sagebionetworks.bridge.android.manager.upload.SchemaKey;
import org.sagebionetworks.bridge.researchstack.wrapper.StorageAccessWrapper;
import org.sagebionetworks.bridge.rest.ApiClientProvider;
import org.sagebionetworks.bridge.rest.api.AuthenticationApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.model.Activity;
import org.sagebionetworks.bridge.rest.model.Message;
import org.sagebionetworks.bridge.rest.model.ScheduledActivity;
import org.sagebionetworks.bridge.rest.model.ScheduledActivityListV4;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.SurveyReference;
import org.sagebionetworks.bridge.rest.model.TaskReference;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import rx.Observable;
import rx.Scheduler;
import rx.android.plugins.RxAndroidPlugins;
import rx.android.plugins.RxAndroidSchedulersHook;
import rx.functions.Action1;
import rx.schedulers.Schedulers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sagebionetworks.bridge.researchstack.CrfDataProvider.ERROR_MISSING_CLINIC_DATA_GROUP;

/**
 * Created by mdephillips on 11/5/17
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({PreferenceManager.class, Looper.class})
public class CrfDataProviderTest {

    private static final String SCHEMA_ID = "my-schema-id";
    private static final int SCHEMA_REV = 3;
    private static final SchemaKey SCHEMA_KEY = new SchemaKey(SCHEMA_ID, SCHEMA_REV);
    private static final String TASK_ID = "my-task-id";

    private MockCrfDataProvider dataProvider;
    @Mock
    private ApiClientProvider apiClientProvider;
    @Mock
    private ForConsentedUsersApi forConsentedUsersApi;
    @Mock
    private AuthenticationApi authenticationApi;
    private StorageAccessWrapper storageAccess;
    private PinCodeConfig pinCodeConfig;
    private FileAccess fileAccess;
    private BridgeEncryptedDatabase appDatabase;
    @Mock
    private TaskHelper taskHelper;

    @Mock
    protected BridgeManagerProvider bridgeManagerProvider;
    @Mock
    protected Context context;
    @Mock
    protected BridgeConfig bridgeConfig;
    @Mock
    protected AccountDAO accountDAO;
    @Mock
    protected ConsentDAO consentDAO;
    @Mock
    protected ResearchStackDAO researchStackDAO;
    @Mock
    protected AuthenticationManager authenticationManager;
    @Mock
    private ActivityManager activityManager;

    @BeforeClass
    public static void setupClass() {
        RxAndroidPlugins.getInstance().registerSchedulersHook(new RxAndroidSchedulersHook() {
            public Scheduler getMainThreadScheduler() {
                return Schedulers.immediate();
            }
        });
    }

    @AfterClass
    public static void cleanupClass() {
        RxAndroidPlugins.getInstance().reset();
    }

    @Before
    public void setupTest() {
        MockitoAnnotations.initMocks(this);
        
        when(bridgeManagerProvider.getApplicationContext()).thenReturn(context);
        when(bridgeManagerProvider.getBridgeConfig()).thenReturn(bridgeConfig);
        when(bridgeManagerProvider.getAccountDao()).thenReturn(accountDAO);
        when(bridgeManagerProvider.getConsentDao()).thenReturn(consentDAO);
        when(bridgeManagerProvider.getAuthenticationManager()).thenReturn(authenticationManager);
        when(bridgeManagerProvider.getActivityManager()).thenReturn(activityManager);

        pinCodeConfig = mock(PinCodeConfig.class);
        fileAccess = mock(FileAccess.class);
        appDatabase = mock(BridgeEncryptedDatabase.class);

        storageAccess = mock(StorageAccessWrapper.class);
        when(storageAccess.getPinCodeConfig()).thenReturn(pinCodeConfig);
        when(storageAccess.getAppDatabase()).thenReturn(appDatabase);
        when(storageAccess.getFileAccess()).thenReturn(fileAccess);

        when(apiClientProvider.getClient(AuthenticationApi.class)).thenReturn(authenticationApi);
        when(apiClientProvider
                .getClient(same(ForConsentedUsersApi.class)))
                .thenReturn(forConsentedUsersApi);

        resetTest(null, true, null);

        PowerMockito.mockStatic(PreferenceManager.class);
        PowerMockito.mockStatic(Looper.class);

        AppPrefs.init(context);
    }

    private void resetTest(DateTime firstSignInDate, boolean assignClinic1, String
            initialDataGroup) {
        dataProvider = new MockCrfDataProvider(
                firstSignInDate, assignClinic1, initialDataGroup,
                researchStackDAO, storageAccess, taskHelper, bridgeManagerProvider);
        dataProvider.mockGetAllActiities(successList(assignClinic1));
        dataProvider.setShouldThrowErrorWithoutClinicDataGroup(true);
    }

    @Test
    public void testInitialize() {
        dataProvider.initialize(context).test().assertCompleted();
    }

    @Test
    public void testFindActivity_clinic1Success() {
        final ScheduledActivityListV4 successList = successList(true);
        final ScheduledActivity clinic = dataProvider.findActivity(successList, "clinic1");
        assertNotNull(clinic);
        assertEquals("clinic1", clinic.getActivity().getTask().getIdentifier());
    }

    @Test
    public void testFindActivity_clinic2Success() {
        final ScheduledActivityListV4 successList = successList(true);
        final ScheduledActivity clinic = dataProvider.findActivity(successList, "clinic2");
        assertNotNull(clinic);
        assertEquals("clinic2", clinic.getActivity().getTask().getIdentifier());
    }

    @Test
    public void testFindActivity_clinic3Null() {
        final ScheduledActivityListV4 successList = successList(true);
        final ScheduledActivity clinic = dataProvider.findActivity(successList, "clinic3");
        assertNull(clinic);
    }

//    @Test
//    public void testAssignClinic1() {
//        resetTest(null, true, "clinic1");
//        dataProvider.setShouldThrowErrorWithoutClinicDataGroup(false);  // will cause assign
//        // clinic id to work
//        final ScheduledActivityListV4 successList = successList(true);
//        dataProvider.mockGetAllActiities(successList);
//        dataProvider.mockGetClinicActiities(clinicActivities(null, null));
//        SchedulesAndTasksModel model = successModel(true);
//        CrfTestListener listener = new CrfTestListener(model, dataProvider.COMPLETION_TIME_CLINIC1);
//        dataProvider.getCrfActivities(false, context, listener);
//    }
//
//    @Test
//    public void testAssignClinic2() {
//        resetTest(null, false, "clinic2");
//        dataProvider.setShouldThrowErrorWithoutClinicDataGroup(false);  // will cause assign
//        // clinic id to work
//        final ScheduledActivityListV4 successList = successList(false);
//        dataProvider.mockGetAllActiities(successList);
//        dataProvider.mockGetClinicActiities(clinicActivities(null, null));
//        SchedulesAndTasksModel model = successModel(false);
//        CrfTestListener listener = new CrfTestListener(model, dataProvider.COMPLETION_TIME_CLINIC2);
//        dataProvider.getCrfActivities(false, context, listener);
//    }
//
//    @Test
//    public void testNoClinicDataGroupFail() {
//        resetTest(null, false, null);
//        final ScheduledActivityListV4 successList = successList(false);
//        dataProvider.mockGetAllActiities(successList);
//        dataProvider.mockGetClinicActiities(clinicActivities(null, null));
//        SchedulesAndTasksModel model = successModel(false);
//        CrfTestListener listener = new CrfTestListener(model, dataProvider.COMPLETION_TIME_CLINIC2) {
//            @Override
//            public void success(SchedulesAndTasksModel model) {
//                fail();
//            }
//
//            @Override
//            public void error(String localizedError) {
//                assertEquals(CrfDataProvider.NO_CLINIC_ERROR_MESSAGE, localizedError);
//            }
//        };
//        dataProvider.getCrfActivities(false, context, listener);
//    }

//    @Test
//    public void testExistingServerDataGroupClinic1() {
//        resetTest(null, true, "clinic1");
//        final ScheduledActivityListV4 successList = successList(true);
//        dataProvider.mockGetAllActiities(successList);
//        dataProvider.mockGetClinicActiities(clinicActivities(null, null));
//        SchedulesAndTasksModel model = successModel(true);
//        CrfTestListener listener = new CrfTestListener(model, dataProvider.COMPLETION_TIME_CLINIC1);
//        dataProvider.getCrfActivities(false, context, listener);
//    }

//    @Test
//    public void testExistingServerDataGroupClinic2() {
//        resetTest(null, false, "clinic2");
//        final ScheduledActivityListV4 successList = successList(false);
//        dataProvider.mockGetAllActiities(successList);
//        dataProvider.mockGetClinicActiities(clinicActivities(null, null));
//        SchedulesAndTasksModel model = successModel(false);
//        CrfTestListener listener = new CrfTestListener(model, dataProvider.COMPLETION_TIME_CLINIC2);
//        dataProvider.getCrfActivities(false, context, listener);
//    }

//    @Test
//    public void testExistingFinishedOnClinic1() {
//        resetTest(null, true, "clinic1");
//        final ScheduledActivityListV4 successList = successList(true);
//        dataProvider.mockGetAllActiities(successList);
//        dataProvider.mockGetClinicActiities(successList);
//        SchedulesAndTasksModel model = successModel(true);
//        CrfTestListener listener = new CrfTestListener(model, dataProvider.COMPLETION_TIME_CLINIC1);
//        dataProvider.getCrfActivities(false, context, listener);
//    }
//
//    @Test
//    public void testExistingFinishedOnClinic2() {
//        resetTest(null, true, "clinic2");
//        final ScheduledActivityListV4 successList = successList(false);
//        dataProvider.mockGetAllActiities(successList);
//        dataProvider.mockGetClinicActiities(successList);
//        SchedulesAndTasksModel model = successModel(false);
//        CrfTestListener listener = new CrfTestListener(model, dataProvider.COMPLETION_TIME_CLINIC2);
//        dataProvider.getCrfActivities(false, context, listener);
//    }

//    @Test
//    public void testExistingFinishedOnInPrefs() {
//        resetTest(dataProvider.COMPLETION_TIME_CLINIC1, true, "clinic2");
//        final ScheduledActivityListV4 successList = successList(false);
//        dataProvider.mockGetAllActiities(successList);
//        dataProvider.mockGetClinicActiities(successList);
//        SchedulesAndTasksModel model = successModel(false);
//        CrfTestListener listener = new CrfTestListener(model, dataProvider.COMPLETION_TIME_CLINIC1);
//        dataProvider.getCrfActivities(false, context, listener);
//    }

//    @Test
//    public void testNoClinicActivitesFail() {
//        resetTest(null, true, null);
//        final ScheduledActivityListV4 failureList = failureList();
//        dataProvider.mockGetAllActiities(failureList);
//        dataProvider.mockGetClinicActiities(failureList);
//        dataProvider.getCrfActivities(false, context, new CrfDataProvider.CrfActivitiesListener() {
//            @Override
//            public void success(SchedulesAndTasksModel model) {
//                fail();
//            }
//
//            @Override
//            public void error(String localizedError) {
//                assertTrue(true);
//            }
//        });
//    }

    @Test
    public void shouldDisplay_persistentActivity() throws Exception {
        Activity a = mock(Activity.class);

        ScheduledActivity sa = mock(ScheduledActivity.class);
        when(sa.getActivity()).thenReturn(a);
        when(sa.getPersistent()).thenReturn(true);

        assertTrue(dataProvider.shouldDisplay(sa));

        verify(sa, atLeastOnce()).getPersistent();
    }

    @Test
    public void testFindOrCreateClinicDate_NoClinicScheduleTriggers() {
        ScheduledActivityListV4 scheduledActivities = mock(ScheduledActivityListV4.class);

        CrfDataProvider.CrfActivitiesListener listener =
                mock(CrfDataProvider.CrfActivitiesListener.class);

        CrfDataProvider spyDataProvider = spy(dataProvider);

        doReturn(Observable.just(scheduledActivities))
                .when(spyDataProvider)
                .getActivities(any(DateTime.class), any(DateTime.class));

        doReturn(Collections.emptyList())
                .when(spyDataProvider).getClinicScheduleTriggers(scheduledActivities);

        spyDataProvider.findOrCreateClinicDate(listener);

        verify(listener).error(ERROR_MISSING_CLINIC_DATA_GROUP);
    }

    @Test
    public void testFindOrCreateClinicDate_ScheduleTriggerCompleted() {
        ScheduledActivityListV4 scheduledActivities = mock(ScheduledActivityListV4.class);

        CrfDataProvider.CrfActivitiesListener listener =
                mock(CrfDataProvider.CrfActivitiesListener.class);

        CrfDataProvider spyDataProvider = spy(dataProvider);

        doReturn(Observable.just(scheduledActivities))
                .when(spyDataProvider)
                .getActivities(any(DateTime.class), any(DateTime.class));

        ScheduledActivity clinic1Trigger = mock(ScheduledActivity.class);
        ScheduledActivity clinic2Trigger = mock(ScheduledActivity.class);
        DateTime finishedOn = DateTime.now();
        when(clinic2Trigger.getFinishedOn()).thenReturn(finishedOn);

        doReturn(Arrays.asList(clinic1Trigger, clinic2Trigger))
                .when(spyDataProvider).getClinicScheduleTriggers(scheduledActivities);

        doNothing().when(spyDataProvider).getCrfActivities(null, listener);

        spyDataProvider.findOrCreateClinicDate(listener);

        assertEquals(spyDataProvider.getCrfPrefs().getClinicDate(), finishedOn);

        verify(spyDataProvider).getCrfActivities(null, listener);
    }

    @Test
    public void testFindOrCreateClinicDate_ClinicDay1Complete() {

    }

    @Test
    public void testFindOrCreateClinicDate_ClinicDay1NotComplete() {

    }

    @Test
    public void shouldDisplay_survey() throws Exception {
        SurveyReference s = mock(SurveyReference.class);

        Activity a = mock(Activity.class);
        when(a.getSurvey()).thenReturn(s);

        ScheduledActivity sa = mock(ScheduledActivity.class);
        when(sa.getActivity()).thenReturn(a);

        assertTrue(dataProvider.shouldDisplay(sa));

        verify(sa, atLeastOnce()).getActivity();
        verify(a, atLeastOnce()).getSurvey();
    }

    @Test
    public void shouldDisplay_displayableTask() throws Exception {
        TaskReference t = mock(TaskReference.class);
        when(t.getIdentifier()).thenReturn("taskId");

        Activity a = mock(Activity.class);
        when(a.getTask()).thenReturn(t);

        ScheduledActivity sa = mock(ScheduledActivity.class);
        when(sa.getActivity()).thenReturn(a);

        assertTrue(dataProvider.shouldDisplay(sa));

        verify(sa, atLeastOnce()).getActivity();
        verify(a, atLeastOnce()).getTask();
    }

    private ScheduledActivityListV4 clinicActivities(DateTime clinic1FinishedOn, DateTime
            clinic2FinishedOn) {
        ScheduledActivityListV4 activitiesModel = new ScheduledActivityListV4();
        List<ScheduledActivity> activitiesList = new ArrayList<>();
        activitiesList.add(activityWith("clinic1", clinic1FinishedOn));
        activitiesList.add(activityWith("clinic2", clinic2FinishedOn));
        activitiesModel.setItems(activitiesList);
        return activitiesModel;
    }

    private SchedulesAndTasksModel successModel(boolean clinic1Complete) {
        SchedulesAndTasksModel model = new SchedulesAndTasksModel();
        List<SchedulesAndTasksModel.ScheduleModel> schedules = new ArrayList<>();
        schedules.add(scheduleWith("success", null));
        schedules.add(scheduleWith("clinic1", clinic1Complete ? dataProvider
                .COMPLETION_TIME_CLINIC1 : null));
        schedules.add(scheduleWith("clinic2", !clinic1Complete ? dataProvider
                .COMPLETION_TIME_CLINIC2 : null));
        model.schedules = schedules;
        return model;
    }

    private ScheduledActivityListV4 successList(boolean clinic1Complete) {
        ScheduledActivityListV4 activitiesModel = new ScheduledActivityListV4();
        List<ScheduledActivity> activitiesList = new ArrayList<>();
        activitiesList.add(activityWith("success", null));
        activitiesList.add(activityWith("clinic1", clinic1Complete ? dataProvider
                .COMPLETION_TIME_CLINIC1 : null));
        activitiesList.add(activityWith("clinic2", !clinic1Complete ? dataProvider
                .COMPLETION_TIME_CLINIC2 : null));
        activitiesModel.setItems(activitiesList);
        return activitiesModel;
    }

    private ScheduledActivityListV4 failureList() {
        ScheduledActivityListV4 activitiesModel = new ScheduledActivityListV4();
        List<ScheduledActivity> activitiesList = new ArrayList<>();
        activitiesList.add(activityWith("success", null));
        activitiesModel.setItems(activitiesList);
        return activitiesModel;
    }

    private ScheduledActivity activityWith(String id, DateTime finishedOn) {

        Activity activity = new Activity();
        TaskReference task = new TaskReference();
        task.setIdentifier(id);
        activity.setTask(task);

        ScheduledActivity sa = mock(ScheduledActivity.class);
        when(sa.getGuid()).thenReturn(UUID.randomUUID().toString());
        when(sa.getSchedulePlanGuid()).thenReturn(UUID.randomUUID().toString());
        when(sa.getScheduledOn()).thenReturn(DateTime.now());
        when(sa.getFinishedOn()).thenReturn(finishedOn);
        when(sa.getActivity()).thenReturn(activity);

        return sa;
    }

    private SchedulesAndTasksModel.ScheduleModel scheduleWith(String id, DateTime finishedOn) {
        SchedulesAndTasksModel.ScheduleModel schedule = new SchedulesAndTasksModel.ScheduleModel();
        schedule.scheduledOn = new Date();
        List<SchedulesAndTasksModel.TaskScheduleModel> tasks = new ArrayList<>();
        SchedulesAndTasksModel.TaskScheduleModel task = new SchedulesAndTasksModel
                .TaskScheduleModel();
        task.taskID = id;
        if (finishedOn != null) {
            task.taskFinishedOn = finishedOn.toDate();
        }
        tasks.add(task);
        schedule.tasks = tasks;
        return schedule;
    }

    private static TaskResult makeActivityTask(String taskId) {
        TaskResult taskResult = new TaskResult(taskId);
        taskResult.getTaskDetails().put(ActiveTaskActivity.ACTIVITY_TASK_RESULT_KEY, true);
        return taskResult;
    }

    private class MockCrfDataProvider extends CrfDataProvider {
        MockCrfPrefs prefs;
        boolean isRandomClient1;
        String initialDataGroup;

        ScheduledActivityListV4 clinicActivities;
        ScheduledActivityListV4 allActivities;

//        private DateTime START_TIME = DateTime.parse("2017-11-01T07:00-0700");
//        private DateTime END_TIME_FOR_CLINICS = DateTime.parse("2017-11-02T07:00-0700");
//        private DateTime END_TIME_FOR_ALL = DateTime.parse("2017-11-15T06:00-0700");

        private DateTime COMPLETION_TIME_CLINIC1 = DateTime.parse("2017-11-01T07:01-0700");
        private DateTime COMPLETION_TIME_CLINIC2 = DateTime.parse("2017-11-01T07:02-0700");

        @VisibleForTesting
        MockCrfDataProvider(DateTime firstSignInDate, boolean assignClinic1, String initialDataGroup,
                            ResearchStackDAO researchStackDAO, StorageAccessWrapper storageAccessWrapper,
                            TaskHelper taskHelper, BridgeManagerProvider bridgeManagerProvider) {

            super(bridgeManagerProvider, researchStackDAO, storageAccessWrapper, taskHelper);
            prefs = new MockCrfPrefs(firstSignInDate);
            isRandomClient1 = assignClinic1;
            this.initialDataGroup = initialDataGroup;
        }

        void mockGetClinicActiities(ScheduledActivityListV4 activityList) {
            clinicActivities = activityList;
        }

        void mockGetAllActiities(ScheduledActivityListV4 activityList) {
            allActivities = activityList;
        }

        @VisibleForTesting
        DateTime createClinicCompletionDate() {
            if (isRandomClient1 || (initialDataGroup != null && initialDataGroup.equals
                    ("clinic1"))) {
                return COMPLETION_TIME_CLINIC1;
            } else {
                return COMPLETION_TIME_CLINIC2;
            }
        }

        @VisibleForTesting
        void setReminders(Context context, SchedulesAndTasksModel model) { /** No-op */}

        @VisibleForTesting
        boolean generateRandomClient() {
            return isRandomClient1;
        }

        @VisibleForTesting
        CrfPrefs getCrfPrefs() {
            return prefs;
        }

        @VisibleForTesting
        void logV(String msg) {
        } // no op

        @VisibleForTesting
        void logE(String msg) {
        } // no op

        @VisibleForTesting
        void debugPrintActivities(ScheduledActivityListV4 activityList) {
        }

//        @VisibleForTesting
//        @Override
//        void getActivitiesSubscribe(DateTime start, DateTime end,
//                                    final Action1<ScheduledActivityListV4> onNext,
//                                    final Action1<Throwable> onError) {
//            if (new Interval(start, end).toDuration().isShorterThan(Duration.standardDays(2))){
//                onNext.call(clinicActivities);
//            } else {
//                onNext.call(allActivities);
//            }
//        }

        @VisibleForTesting
        @Override
        void getStudyParticipantSubscribe(final Action1<StudyParticipant> onNext,
                                          final Action1<Throwable> onError) {
            StudyParticipant studyParticipant = new StudyParticipant();
            studyParticipant.setDataGroups(initialDataGroup != null
                    ? Collections.singletonList(initialDataGroup): Collections.emptyList());
            onNext.call(studyParticipant);
        }

        @VisibleForTesting
        @Override
        void updateStudyParticipantSubscribe(StudyParticipant studyParticipant,
                                             final Action1<UserSessionInfo> onNext,
                                             final Action1<Throwable> onError) {
            // Test if provider is assigning the user the expected clinic data group
            if (isRandomClient1) {
                assertTrue(studyParticipant.getDataGroups().contains("clinic1"));
            } else {
                assertTrue(studyParticipant.getDataGroups().contains("clinic2"));
            }
            onNext.call(new UserSessionInfo());
        }

        @VisibleForTesting
        @Override
        void updateActivitySubscribe(ScheduledActivity activity,
                                     final Action1<Message> onNext,
                                     final Action1<Throwable> onError) {

            // Test if provider is completing the expected clinic
            if (isRandomClient1 || (initialDataGroup != null && initialDataGroup.equals
                    ("clinic1"))) {
                assertEquals("clinic1", activity.getActivity().getTask().getIdentifier());
            } else {
                assertEquals("clinic2", activity.getActivity().getTask().getIdentifier());
            }
            onNext.call(new Message());
        }

        @VisibleForTesting
        @Override
        void signOutSubscribe(Context context,
                              final Action1<DataResponse> onNext,
                              final Action1<Throwable> onError) {
            onNext.call(new DataResponse());
        }


        @NonNull
        @Override
        protected SchedulesAndTasksModel translateActivities(@NonNull List<ScheduledActivity>
                                                                     activityList) {
            // Do a super simple translate activities
            SchedulesAndTasksModel model = new SchedulesAndTasksModel();
            model.schedules = new ArrayList<>();
            for (ScheduledActivity activity : activityList) {
                model.schedules.add(scheduleWith(activity.getActivity().getTask().getIdentifier()
                        , activity.getFinishedOn()));
            }
            return model;
        }
    }

    private class MockCrfPrefs extends CrfPrefs {
        private DateTime firstSignInDate = null;

        MockCrfPrefs(DateTime firstSignInDateTime) {
            super(context);
            this.firstSignInDate = firstSignInDateTime;
        }

        SharedPreferences createPrefs(Context context) {
            return null;
        }

        public DateTime getClinicDate() {
            return firstSignInDate;
        }

        public void setClinicDate(DateTime dateTime) {
            this.firstSignInDate = dateTime;
        }
    }

    private class CrfTestListener implements CrfDataProvider.CrfActivitiesListener {

        private SchedulesAndTasksModel successModel;
        private DateTime completionTime;

        CrfTestListener(SchedulesAndTasksModel successModel, DateTime completionTime) {
            this.successModel = successModel;
            this.completionTime = completionTime;
        }

        @Override
        public void success(SchedulesAndTasksModel model) {
            // Check that the returned model has the same task IDs and finishedOn dates as the
            // successModel
            for (SchedulesAndTasksModel.ScheduleModel schedule : model.schedules) {
                boolean taskIsTheSame = false;
                for (SchedulesAndTasksModel.TaskScheduleModel task : schedule.tasks) {
                    for (SchedulesAndTasksModel.ScheduleModel expectedSchedule : model.schedules) {
                        for (SchedulesAndTasksModel.TaskScheduleModel expectedTask : schedule
                                .tasks) {
                            if (expectedTask.taskID.equals(task.taskID)) {
                                if ((expectedTask.taskFinishedOn == null && task.taskFinishedOn
                                        == null) ||
                                        expectedTask.taskFinishedOn.equals(task.taskFinishedOn)) {
                                    taskIsTheSame = true;
                                }
                            }
                        }
                    }
                }
                assertTrue(taskIsTheSame);
            }

            assertEquals(dataProvider.getCrfPrefs().getClinicDate(), completionTime);
        }

        @Override
        public void error(String localizedError) {
            fail();
        }
    }
}