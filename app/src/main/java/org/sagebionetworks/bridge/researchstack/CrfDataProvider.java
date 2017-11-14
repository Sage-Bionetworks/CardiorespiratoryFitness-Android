package org.sagebionetworks.bridge.researchstack;

import android.content.Context;
import android.support.annotation.VisibleForTesting;
import android.util.Log;
import android.view.View;

import org.joda.time.DateTime;
import org.researchstack.backbone.DataProvider;
import org.researchstack.backbone.DataResponse;
import org.researchstack.backbone.ResourceManager;
import org.researchstack.backbone.model.User;
import org.researchstack.backbone.result.TaskResult;
import org.researchstack.backbone.storage.NotificationHelper;
import org.researchstack.skin.AppPrefs;
import org.sagebionetworks.bridge.android.manager.BridgeManagerProvider;
import org.sagebionetworks.bridge.researchstack.wrapper.StorageAccessWrapper;
import org.sagebionetworks.bridge.rest.model.Message;
import org.sagebionetworks.bridge.rest.model.ScheduledActivity;
import org.sagebionetworks.bridge.rest.model.ScheduledActivityListV4;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.UserSessionInfo;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import rx.Completable;
import rx.Observable;
import rx.Single;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action0;
import rx.functions.Action1;

/**
 * Created by TheMDP on 12/12/16.
 */

public class CrfDataProvider extends BridgeDataProvider {
    private static final String LOG_TAG = CrfDataProvider.class.getCanonicalName();

    public static final String CLINIC1 = "clinic1";
    public static final String CLINIC2 = "clinic2";

    public static final int STUDY_DURATION_IN_DAYS = 15;

    public CrfDataProvider() {
        // TODO give path to permission file for uploads
        super(BridgeManagerProvider.getInstance());
    }

    @VisibleForTesting
    CrfDataProvider(ResearchStackDAO researchStackDAO, StorageAccessWrapper storageAccessWrapper,
                       TaskHelper taskHelper) {
        super(researchStackDAO, storageAccessWrapper, taskHelper);
    }

    @Override
    public TaskHelper createTaskHelper(NotificationHelper notif, StorageAccessWrapper wrapper, BridgeManagerProvider provider) {
        return new CrfTaskHelper(wrapper, ResourceManager.getInstance(), AppPrefs.getInstance(), notif, provider);
    }

    @Override
    public void processInitialTaskResult(Context context, TaskResult taskResult) {
        // TODO: what do we do with this method?
    }

    public Observable<DataResponse> signOut(Context context) {
        return super.signOut(context)
                .doOnCompleted(new Action0() {
                    @Override
                    public void call() {
                        CrfPrefs.getInstance().clear();
                    }
                });
    }

    /**
     * This method hides the complex logic of the CRF scheduling system
     * and simply returns the activities, or an error if something went wrong
     * @param listener the callback listener for the events
     */
    public void getCrfActivities(final CrfActivitiesListener listener) {

        if (!getCrfPrefs().hasFirstSignInDate()) {
            logV("No sign in date detected");
            // getCrfActivities method will be called again when sign in date is found, so return here
            createOrFindFirstSignInDate(listener);
            return;
        }

        DateTime firstSignInDate = getCrfPrefs().getFirstSignInDate();
        logV(String.format(Locale.getDefault(),
                "Previous sign in date detected %s", firstSignInDate.toString()));
        // We have already done the clinic setup process, and can safely grab the schedules
        getActivitiesSubscribe(firstSignInDate, endTimeForAllActivities(firstSignInDate), activityList -> {
            debugPrintActivities(activityList);
            listener.success(activityList);
        }, throwable -> listener.error(throwable.getLocalizedMessage()));
    }

    /**
     * A first sign in date is needed to get the study's activities
     * The first sign in date represents the finished on date of either CLINIC1 or CLINIC2 activities
     * This method will try and find the clinic activities to see if one of them is already finished
     * Or it may continue trying to find the sign in date, or trigger the process to create one
     * @param listener the listener for success/fail response
     */
    private void createOrFindFirstSignInDate(final CrfActivitiesListener listener) {
        logV("createOrFindFirstSignInDate");
        getActivitiesSubscribe(startTime(), endTimeForClinicActivities(), activityList -> {

            // We are only interested in the clinic1 and clinic2 activities
            ScheduledActivity clinic1 = findActivity(activityList, CLINIC1);
            ScheduledActivity clinic2 = findActivity(activityList, CLINIC2);

            if (clinic1 == null || clinic2 == null) {
                logE("We must have clinic1 or clinic 2 activities to continue, are you in the correct data groups?");
                listener.error("Error: could not find both clinic1 and clinic2, are you in the correct data groups?");
                return;
            }

            logV(String.format(Locale.getDefault(),"Clinic1 = %s", clinic1.toString()));
            logV(String.format(Locale.getDefault(),"Clinic2 = %s", clinic2.toString()));

            // Whichever clinic activity is finished is the one this user is a part of
            if (clinic1.getFinishedOn() != null) {  // Found date, go back to loading activities
                logV(String.format(Locale.getDefault(),
                        "Setting firstSignInDate on clinic1 = %s", clinic1.getFinishedOn().toString()));
                getCrfPrefs().setFirstSignInDate(clinic1.getFinishedOn());
                getCrfActivities(listener);
            } else if (clinic2.getFinishedOn() != null) { // Found date, go back to loading activities
                getCrfPrefs().setFirstSignInDate(clinic2.getFinishedOn());
                logV(String.format(Locale.getDefault(),
                        "Setting firstSignInDate on clinic1 = %s", clinic2.getFinishedOn().toString()));
                getCrfActivities(listener);
            } else {
                // Otherwise, this is the user's first sign in, let's find or assign their clinic group
                findOrCreateClinicGroup(listener, clinic1, clinic2);
            }
        }, throwable -> listener.error(throwable.getLocalizedMessage()));
    }

    @VisibleForTesting
    void getActivitiesSubscribe(DateTime start, DateTime end,
                                final Action1<ScheduledActivityListV4> onNext,
                                final Action1<Throwable> onError) {
        getActivities(start, end).observeOn(AndroidSchedulers.mainThread()).subscribe(onNext, onError);
    }

    /**
     * @param listener the listener for success/fail response
     * @param clinic1 the scheduled activity that will trigger clinic1 group
     * @param clinic2 the scheduled activity that will trigger clinic2 group
     */
    private void findOrCreateClinicGroup(
            final CrfActivitiesListener listener,
            ScheduledActivity clinic1, ScheduledActivity clinic2) {

        logV("findOrCreateClinicGroup");

        // First let's check if the user has a clinic data group already
        // This check also allows the server to pre-populate clinic groups
        getStudyParticipantSubscribe(studyParticipant -> {
            List<String> dataGroups = studyParticipant.getDataGroups();

            if (dataGroups != null) {
                logV("dataGroups = " + dataGroups.toString());
            }

            if (dataGroups == null ||
                dataGroups.isEmpty() ||
                (!dataGroups.contains(CLINIC1) || !dataGroups.contains(CLINIC1))) {
                assignRandomizedClinic(dataGroups, listener, clinic1, clinic2);
            } else {
                // We already have the clinic data group assigned, so simply read it and
                // trigger the corresponding clinic activity schedule flow
                ScheduledActivity clinic = dataGroups.contains(CLINIC1) ? clinic1 : clinic2;
                completeClinicSchedule(clinic, listener);
            }
        }, throwable -> listener.error(throwable.getLocalizedMessage()));
    }

    @VisibleForTesting
    void getStudyParticipantSubscribe(final Action1<StudyParticipant> onNext,
                                      final Action1<Throwable> onError) {
        getStudyParticipant().observeOn(AndroidSchedulers.mainThread()).subscribe(onNext, onError);
    }

    /**
     * @param dataGroups the data groups from the study participant
     * @param listener the listener for success/fail response
     * @param clinic1 the scheduled activity that will trigger clinic1 group
     * @param clinic2 the scheduled activity that will trigger clinic2 group
     */
    private void assignRandomizedClinic(
            List<String> dataGroups, final CrfActivitiesListener listener,
            ScheduledActivity clinic1, ScheduledActivity clinic2) {

        logV("assignRandomizedClinic");

        final boolean assignToClinic1 = generateRandomClient();
        ScheduledActivity chosenClinic;
        String chosenClinicDataGroup;
        if (assignToClinic1) {
            chosenClinic = clinic1;
            chosenClinicDataGroup = CLINIC1;
        } else {  // clinic 2
            chosenClinic = clinic2;
            chosenClinicDataGroup = CLINIC2;
        }

        StudyParticipant participant = new StudyParticipant();
        List<String> newDataGroups = Collections.singletonList(chosenClinicDataGroup);
        if (dataGroups != null) {
            newDataGroups = new ArrayList<>(dataGroups);
            newDataGroups.add(chosenClinicDataGroup);
        }
        participant.setDataGroups(newDataGroups);

        logV("Random data group assigned " + chosenClinicDataGroup);

        updateStudyParticipantSubscribe(participant, userSessionInfo -> {
            logV("Data group successfully assigned");
            // We already have the clinic data group assigned, so
            // trigger the corresponding clinic activity schedule flow
            completeClinicSchedule(chosenClinic, listener);
        }, throwable -> listener.error(throwable.getLocalizedMessage()));
    }

    @VisibleForTesting
    void updateStudyParticipantSubscribe(StudyParticipant studyParticipant,
                                         final Action1<UserSessionInfo> onNext,
                                         final Action1<Throwable> onError) {
        updateStudyParticipant(studyParticipant).observeOn(AndroidSchedulers.mainThread()).subscribe(onNext, onError);
    }

    /**
     * @param clinic this will be the activity representing the clinic this user will be a part of
     * @param listener the listener for success/fail response
     */
    private void completeClinicSchedule(final ScheduledActivity clinic,
                                        final CrfActivitiesListener listener) {
        logV("Completing " + clinic.toString());

        // These fields are what is needed to trigger the completion of an activity
        final DateTime completed = createClinicCompletionDate();
        clinic.setStartedOn(completed);
        clinic.setFinishedOn(completed);

        updateActivitySubscribe(clinic, message -> {
            logV("Completed clinic successful");
            // We have completed the clinic activity which will automatically
            // trigger the app's clinic schedules and we can simply pull activities now
            getCrfPrefs().setFirstSignInDate(completed);
            getCrfActivities(listener);
        }, throwable -> listener.error(throwable.getLocalizedMessage()));
    }

    @VisibleForTesting
    void updateActivitySubscribe(ScheduledActivity activity,
                                 final Action1<Message> onNext,
                                 final Action1<Throwable> onError) {
        updateActivity(activity).observeOn(AndroidSchedulers.mainThread()).subscribe(onNext, onError);
    }

    @VisibleForTesting
    DateTime startTime() {
        return DateTime.now();
    }

    @VisibleForTesting
    DateTime endTimeForClinicActivities() {
        return addTime(DateTime.now(), 1, 0);
    }

    @VisibleForTesting
    DateTime endTimeForAllActivities(DateTime firstSignInDate) {
        return firstSignInDate.plusDays(STUDY_DURATION_IN_DAYS).minusHours(1);
    }

    @VisibleForTesting
    DateTime createClinicCompletionDate() {
        return DateTime.now();
    }

    @VisibleForTesting
    boolean generateRandomClient() {
        return new Random().nextBoolean();
    }

    @VisibleForTesting
    CrfPrefs getCrfPrefs() {
        return CrfPrefs.getInstance();
    }

    @VisibleForTesting
    void logV(String msg) {
        Log.v(LOG_TAG, msg);
    }

    @VisibleForTesting
    void logE(String msg) {
        Log.e(LOG_TAG, msg);
    }

    /**
     * @param activityList to search through
     * @param identifier of the activity to return
     * @return activity with identifier, or null if none was found
     */
    @VisibleForTesting
    ScheduledActivity findActivity(ScheduledActivityListV4 activityList, String identifier) {
        if (activityList == null || activityList.getItems() == null || activityList.getItems().isEmpty()) {
            return null;
        }
        for (ScheduledActivity activity : activityList.getItems()) {
            if (activity.getActivity() != null && activity.getActivity().getTask() != null) {
                if (activity.getActivity().getTask().getIdentifier() != null) {
                    if (activity.getActivity().getTask().getIdentifier().equals(identifier)) {
                        return activity;
                    }
                }
            }
        }
        return null;
    }

    /**
     * addDays method will safely calculate days in the future that will always have the same timezone
     * this fixes a bug where daylight savings time and DateTime will create different timezones
     * and the bridge server will reject the call
     * @param dateTime
     * @param days
     * @return
     */
    DateTime addTime(DateTime dateTime, int days, int hours) {
        DateTime newDateTime = dateTime.plusDays(days).plusHours(hours);

        // TODO: do this without string manipulation somehow and also add hours/mins based on what was lost
//        String oldDateTimeStr = CrfPrefs.FORMATTER.print(dateTime);
//        int oldOffsetStartIdx = oldDateTimeStr.lastIndexOf("-");
//        String oldOffsetStr = oldDateTimeStr.substring(oldOffsetStartIdx, oldDateTimeStr.length());
//
//        String newDateTimeStr = CrfPrefs.FORMATTER.print(newDateTime);
//        int newOffsetStartIdx = newDateTimeStr.lastIndexOf("-");
//        String newOffsetStr = newDateTimeStr.substring(newOffsetStartIdx, newDateTimeStr.length());

        // Bridge server does not like when we request date ranges with different time zones
        // Unfortunately, during daylight savings, DateTime automatically switches time zones
        // when we use the method "plusDays", so we must correct that if we determine it happened
//        if (!oldOffsetStr.equals(newOffsetStr)) {
//            // We had a time zone change!
//            Log.d(LOG_TAG, "Time zone change detected, correcting error");
//            String offsetCorrectDateTimeStr =
//                    newDateTimeStr.substring(0, newOffsetStartIdx) + oldOffsetStr;
//            newDateTime = CrfPrefs.FORMATTER.parseDateTime(offsetCorrectDateTimeStr);
//        }

        return newDateTime;
    }

    @VisibleForTesting
    void debugPrintActivities(ScheduledActivityListV4 activityList) {
        StringBuilder debugActivityList = new StringBuilder();
        for (ScheduledActivity activity : activityList.getItems()) {
            if (!activity.getPersistent()) {
                if (activity.getActivity().getTask() == null) {
                    debugActivityList.append(activity.getActivity().getSurvey().getIdentifier());
                } else {
                    debugActivityList.append(activity.getActivity().getTask().getIdentifier());
                }
                debugActivityList.append(" on ");
                debugActivityList.append(activity.getScheduledOn().toString());
                debugActivityList.append("\n");
            }
        }
        logV(debugActivityList.toString());
    }

    public interface CrfActivitiesListener {
        void success(ScheduledActivityListV4 activityList);
        void error(String localizedError);
    }
}