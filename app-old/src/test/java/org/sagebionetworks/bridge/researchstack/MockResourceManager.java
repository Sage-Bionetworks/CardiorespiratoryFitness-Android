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

import org.mockito.Matchers;
import org.powermock.api.mockito.PowerMockito;
import org.sagebionetworks.researchstack.backbone.ResourceManager;
import org.sagebionetworks.researchstack.backbone.ResourcePathManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import static org.junit.Assert.assertTrue;

/**
 * Created by TheMDP on 3/28/17.
 */

public class MockResourceManager extends ResourceManager {

    private String onboardingJsonName;

    public MockResourceManager() {
        onboardingJsonName = "onboarding";
    }

    public void addReference(int type, String resourceName) {
        addResource(resourceName, new Resource(type, null, resourceName));
        if (type == Resource.TYPE_HTML) {
            PowerMockito
                    .when(ResourcePathManager.getResourceAsString(Matchers.any(), Matchers.anyString()))
                    .thenReturn(resourceName);
        } else {
            PowerMockito
                    .when(ResourcePathManager.getResourceAsString(Matchers.any(), Matchers.matches(resourceName)))
                    .thenReturn(getJsonStringForResourceName(resourceName));
        }
    }

    private String getJsonStringForResourceName(String resourceName) {
        // Resources are in src/test/resources
        InputStream jsonStream = getClass().getClassLoader().getResourceAsStream(resourceName+".json");
        String json = convertStreamToString(jsonStream);
        return json;
    }

    private String convertStreamToString(InputStream is) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();

        String line = null;
        try {
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            assertTrue("Failed to read stream", false);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                assertTrue("Failed to read stream", false);
            }
        }
        return sb.toString();
    }

    @Override
    public Resource getStudyOverview() {
        return null;
    }

    @Override
    public Resource getConsentHtml() {
        return null;
    }

    @Override
    public Resource getConsentPDF() {
        return null;
    }

    @Override
    public Resource getLicense() {
        return null;
    }

    @Override
    public Resource getConsentSections() {
        return null;
    }

    @Override
    public Resource getLearnSections() {
        return null;
    }

    @Override
    public Resource getPrivacyPolicy() {
        return null;
    }

    @Override
    public Resource getSoftwareNotices() {
        return null;
    }

    @Override
    public Resource getTasksAndSchedules() {
        return null;
    }

    @Override
    public Resource getTask(String taskFileName) {
        return null;
    }

    @Override
    public Resource getInclusionCriteria() {
        return null;
    }

    @Override
    public Resource getOnboardingManager() {
        return new Resource(Resource.TYPE_JSON, null, onboardingJsonName);
    }

    @Override
    public String generatePath(int type, String name) {
        return name;
    }

    @Override
    public String generateAbsolutePath(int type, String name) {
        return name;
    }

    public String getOnboardingJsonName() {
        return onboardingJsonName;
    }

    public void setOnboardingJsonName(String onboardingJsonName) {
        this.onboardingJsonName = onboardingJsonName;
    }
}
