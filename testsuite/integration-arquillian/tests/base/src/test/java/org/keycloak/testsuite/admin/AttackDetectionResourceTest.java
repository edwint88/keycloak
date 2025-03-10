/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.testsuite.admin;

import org.jboss.arquillian.test.api.ArquillianResource;
import org.junit.Test;
import org.keycloak.admin.client.resource.AttackDetectionResource;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.testsuite.util.AdminEventPaths;
import org.keycloak.testsuite.util.oauth.OAuthClient;
import org.keycloak.testsuite.util.UserBuilder;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.keycloak.testsuite.auth.page.AuthRealm.TEST;

/**
 *
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class AttackDetectionResourceTest extends AbstractAdminTest {

    @ArquillianResource
    private OAuthClient oauthClient;

    @Override
    public void configureTestRealm(RealmRepresentation testRealm) {
        testRealm.setBruteForceProtected(true);
        testRealm.setFailureFactor(2);

        testRealm.getClients().stream().filter(c -> c.getClientId().equals("test-app")).forEach(c -> c.setDirectAccessGrantsEnabled(true));

        testRealm.getUsers().add(UserBuilder.create().username("test-user2").password("password").build());
    }

    @Test
    public void test() {
        AttackDetectionResource detection = adminClient.realm(TEST).attackDetection();
        String realmId = adminClient.realm(TEST).toRepresentation().getId();

        assertBruteForce(detection.bruteForceUserStatus(findUser("test-user@localhost").getId()), 0, 0, false, false);

        oauthClient.doPasswordGrantRequest("test-user@localhost", "invalid");
        oauthClient.doPasswordGrantRequest("test-user@localhost", "invalid");
        oauthClient.doPasswordGrantRequest("test-user@localhost", "invalid");

        oauthClient.doPasswordGrantRequest("test-user2", "invalid");
        oauthClient.doPasswordGrantRequest("test-user2", "invalid");
        oauthClient.doPasswordGrantRequest("nosuchuser", "invalid");

        assertBruteForce(detection.bruteForceUserStatus(findUser("test-user@localhost").getId()), 2, 1, true, true);
        assertBruteForce(detection.bruteForceUserStatus(findUser("test-user2").getId()), 2, 1, true, true);
        assertBruteForce(detection.bruteForceUserStatus("nosuchuser"), 0, 0, false, false);

        detection.clearBruteForceForUser(findUser("test-user@localhost").getId());
        assertAdminEvents.assertEvent(realmId, OperationType.DELETE, AdminEventPaths.attackDetectionClearBruteForceForUserPath(findUser("test-user@localhost").getId()), ResourceType.USER_LOGIN_FAILURE);

        assertBruteForce(detection.bruteForceUserStatus(findUser("test-user@localhost").getId()), 0, 0, false, false);
        assertBruteForce(detection.bruteForceUserStatus(findUser("test-user2").getId()), 2, 1, true, true);

        detection.clearAllBruteForce();
        assertAdminEvents.assertEvent(realmId, OperationType.DELETE, AdminEventPaths.attackDetectionClearAllBruteForcePath(), ResourceType.USER_LOGIN_FAILURE);

        assertBruteForce(detection.bruteForceUserStatus(findUser("test-user@localhost").getId()), 0, 0, false, false);
        assertBruteForce(detection.bruteForceUserStatus(findUser("test-user2").getId()), 0, 0, false, false);
    }

    private void assertBruteForce(Map<String, Object> status, Integer expectedNumFailures, Integer expectedNumTemporaryLockouts, Boolean expectedFailure, Boolean expectedDisabled) {
        assertEquals(6, status.size());
        assertEquals(expectedNumFailures, status.get("numFailures"));
        assertEquals(expectedNumTemporaryLockouts, status.get("numTemporaryLockouts"));
        assertEquals(expectedDisabled, status.get("disabled"));
        if (expectedFailure) {
            assertEquals("127.0.0.1", status.get("lastIPFailure"));
            Long lastFailure = (Long) status.get("lastFailure");
            assertTrue(lastFailure < (System.currentTimeMillis() + 1) && lastFailure > (System.currentTimeMillis() - 10000));
            assertNotEquals("0", status.get("failedLoginNotBefore").toString());
        } else {
            assertEquals("n/a", status.get("lastIPFailure"));
            assertEquals("0", status.get("lastFailure").toString());
            assertEquals("0", status.get("failedLoginNotBefore").toString());
        }
    }

}
