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

package org.sagebase.crf.view;

import android.content.Context;

import org.sagebase.crf.fitbit.model.Activity;

/**
 * Created by TheMDP on 10/31/17.
 */

public interface CrfTaskToolbarActionManipulator {
    /**
     * Called when the right icon is clicked
     * @return true if click event was consumed, false if default behavior should occur
     */
    boolean crfToolbarRightIconClicked();
}
