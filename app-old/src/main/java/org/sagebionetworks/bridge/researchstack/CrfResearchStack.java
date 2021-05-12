package org.sagebionetworks.bridge.researchstack;

import android.content.Context;

import org.sagebionetworks.researchstack.backbone.storage.database.AppDatabase;
import org.sagebionetworks.researchstack.backbone.storage.file.EncryptionProvider;
import org.sagebionetworks.researchstack.backbone.storage.file.FileAccess;
import org.sagebionetworks.researchstack.backbone.storage.file.PinCodeConfig;
import org.sagebionetworks.researchstack.backbone.storage.file.SimpleFileAccess;
import org.sagebionetworks.researchstack.backbone.storage.file.aes.AesProvider;
import org.sagebionetworks.researchstack.backbone.AppPrefs;
import org.sagebionetworks.researchstack.backbone.DataProvider;
import org.sagebionetworks.researchstack.backbone.PermissionRequestManager;
import org.sagebionetworks.researchstack.backbone.ResearchStack;
import org.sagebionetworks.researchstack.backbone.ResourceManager;
import org.sagebionetworks.researchstack.backbone.TaskProvider;
import org.sagebionetworks.researchstack.backbone.UiManager;
import org.sagebionetworks.researchstack.backbone.notification.NotificationConfig;
import org.sagebionetworks.researchstack.backbone.notification.SimpleNotificationConfig;
import org.sagebionetworks.researchstack.backbone.onboarding.OnboardingManager;
import org.sagebase.crf.researchstack.CrfResourceManager;
import org.sagebionetworks.bridge.android.manager.BridgeManagerProvider;

/**
 * Created by TheMDP on 12/12/16.
 */

public class CrfResearchStack extends ResearchStack {

    CrfEmptyAppDatabase mEmptyDb;
    AesProvider mEncryptionProvider;

    CrfResourceManager mResourceManager;
    CrfUiManager mUiManager;

    CrfDataProvider mDataProvider;

    SimpleFileAccess mFileAccess;
    PinCodeConfig mPinCodeConfig;

    TaskProvider mTaskProvider;

    SimpleNotificationConfig mNotificationConfig;

    CrfPermissionRequestManager mPermissionManager;

    CrfOnboardingManager mOnboardingManager;

    public CrfResearchStack(Context context) {

        CrfPrefs.init(context);

        mFileAccess = new SimpleFileAccess();

        mEncryptionProvider = new AesProvider();

        mResourceManager = new CrfResourceManager();

        mNotificationConfig = new SimpleNotificationConfig();

        mPermissionManager = new CrfPermissionRequestManager();
    }

    @Override
    protected AppDatabase createAppDatabaseImplementation(Context context) {
        if (mEmptyDb == null) {
            mEmptyDb = new CrfEmptyAppDatabase();
        }
        return mEmptyDb;
    }

    @Override
    protected FileAccess createFileAccessImplementation(Context context) {
        return mFileAccess;
    }

    @Override
    protected PinCodeConfig getPinCodeConfig(Context context) {
        if (mPinCodeConfig == null) {
            long autoLockTime = AppPrefs.getInstance(context).getAutoLockTime();
            mPinCodeConfig = new PinCodeConfig(autoLockTime);
        }
        return mPinCodeConfig;
    }

    @Override
    protected EncryptionProvider getEncryptionProvider(Context context) {
        return mEncryptionProvider;
    }

    @Override
    protected ResourceManager createResourceManagerImplementation(Context context) {
        return mResourceManager;
    }

    @Override
    protected UiManager createUiManagerImplementation(Context context) {
        if (mUiManager == null) {
            mUiManager = new CrfUiManager();
        }
        return mUiManager;
    }

    @Override
    protected DataProvider createDataProviderImplementation(Context context) {
        if (mDataProvider == null) {
            mDataProvider = new CrfDataProvider(BridgeManagerProvider.getInstance());
        }
        return mDataProvider;
    }

    @Override
    protected TaskProvider createTaskProviderImplementation(Context context) {
        if (mTaskProvider == null) {
            mTaskProvider = new CrfTaskProvider(context);
        }
        return mTaskProvider;
    }

    @Override
    protected NotificationConfig createNotificationConfigImplementation(Context context) {
        return mNotificationConfig;
    }

    @Override
    protected PermissionRequestManager createPermissionRequestManagerImplementation(Context context) {
        return mPermissionManager;
    }

    @Override
    public OnboardingManager getOnboardingManager() {
        return mOnboardingManager;
    }

    @Override
    public void createOnboardingManager(Context context) {
        mOnboardingManager = new CrfOnboardingManager(context);
    }
}
