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

package org.sagebase.crf.step;

import org.researchstack.backbone.step.active.ActiveStep;
import org.researchstack.backbone.step.active.recorder.AccelerometerRecorderConfig;
import org.researchstack.backbone.step.active.recorder.DeviceMotionRecorderConfig;
import org.researchstack.backbone.step.active.recorder.LocationRecorderConfig;
import org.researchstack.backbone.step.active.recorder.RecorderConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by TheMDP on 10/31/17.
 */

public class Crf12MinWalkingStep extends ActiveStep {

    public static final String LOCATION_RECORDER_ID = "location";
    public static final String MOTION_RECORDER_ID = "motion";

    public static final int SENSOR_FREQ = 100;

    public Crf12MinWalkingStep(String identifier) {
        super(identifier);
        commonInit();
    }

    public Crf12MinWalkingStep(String identifier, String title, String detailText) {
        super(identifier, title, detailText);
        commonInit();
    }

    private void commonInit() {
        setStepDuration(12 * 60); // 12 min
        List<RecorderConfig> configList = new ArrayList<>();
        configList.add(new LocationRecorderConfig(LOCATION_RECORDER_ID));
        configList.add(new DeviceMotionRecorderConfig(MOTION_RECORDER_ID, SENSOR_FREQ));
        setRecorderConfigurationList(configList);
        setShouldContinueOnFinish(true);
        setShouldStartTimerAutomatically(true);
    }

    @Override
    public Class getStepLayoutClass() {
        return Crf12MinWalkingStepLayout.class;
    }
}
