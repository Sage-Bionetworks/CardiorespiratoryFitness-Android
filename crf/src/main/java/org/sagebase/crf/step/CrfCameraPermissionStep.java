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

package org.sagebase.crf.step;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

public class CrfCameraPermissionStep extends CrfInstructionStep {

    /* Default constructor needed for serialization/deserialization of object */
    public CrfCameraPermissionStep() {
        super();
    }

    public CrfCameraPermissionStep(String identifier, String title, String detailText) {
        super(identifier, title, detailText);
    }

    @Override
    public Class getStepLayoutClass() {
        return CrfCameraPermissionStepLayout.class;
    }

}
