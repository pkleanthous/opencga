/*
 * Copyright 2015 OpenCB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opencb.opencga.catalog.db.mongodb;

import org.junit.Test;
import org.opencb.commons.datastore.core.ObjectMap;
import org.opencb.commons.datastore.core.Query;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.commons.datastore.core.QueryResult;
import org.opencb.opencga.catalog.exceptions.CatalogDBException;
import org.opencb.opencga.catalog.models.Session;
import org.opencb.opencga.catalog.models.Status;
import org.opencb.opencga.catalog.models.User;
import org.opencb.opencga.core.common.TimeUtils;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Created by pfurio on 19/01/16.
 */
public class CatalogMongoUserDBAdaptorTest extends CatalogMongoDBAdaptorTest {

    @Test
    public void nativeGet() throws Exception {
        Query query = new Query("id", "imedina");
        QueryResult queryResult = catalogUserDBAdaptor.nativeGet(query, null);
    }

    @Test
    public void createUserTest() throws CatalogDBException {

        User user = new User("NewUser", "", "", "", "", User.Role.USER, new Status());
        QueryResult createUser = catalogUserDBAdaptor.insertUser(user, null);
        assertNotSame(0, createUser.getResult().size());

        thrown.expect(CatalogDBException.class);
        catalogUserDBAdaptor.insertUser(user, null);
    }

    @Test
    public void deleteUserTest() throws CatalogDBException {
        User deletable1 = new User("deletable1", "deletable 1", "d1@ebi", "1234", "", User.Role.USER, new Status());
        QueryResult createUser = catalogUserDBAdaptor.insertUser(deletable1, null);
        assertFalse(createUser.getResult().isEmpty());
        assertNotNull(createUser.first());

        QueryResult deleteUser = catalogUserDBAdaptor.delete(deletable1.getId());
        assertFalse(deleteUser.getResult().isEmpty());
        assertNotNull(deleteUser.first());

        thrown.expect(CatalogDBException.class);
        catalogUserDBAdaptor.delete(deletable1.getId());
    }

    @Test
    public void getUserTest() throws CatalogDBException {
        QueryResult<User> user = catalogUserDBAdaptor.getUser(user1.getId(), null, null);
        assertNotSame(0, user.getResult().size());

        user = catalogUserDBAdaptor.getUser(user3.getId(), null, null);
        assertFalse(user.getResult().isEmpty());
        assertFalse(user.first().getProjects().isEmpty());

        user = catalogUserDBAdaptor.getUser(user3.getId(), new QueryOptions("exclude", Arrays.asList("projects")), null);
        assertNull(user.first().getProjects());

        user = catalogUserDBAdaptor.getUser(user3.getId(), null, user.first().getLastActivity());
        assertTrue(user.getResult().isEmpty());

        thrown.expect(CatalogDBException.class);
        catalogUserDBAdaptor.getUser("NonExistingUser", null, null);
    }

    @Test
    public void loginTest() throws CatalogDBException, IOException {
        String userId = user1.getId();
        Session sessionJCOLL = new Session("127.0.0.1");
        QueryResult<ObjectMap> login = catalogUserDBAdaptor.login(userId, "1234", sessionJCOLL);
        assertEquals(userId, login.first().getString("userId"));

        thrown.expect(CatalogDBException.class);
        catalogUserDBAdaptor.login(userId, "INVALID_PASSWORD", sessionJCOLL);
    }

    @Test
    public void loginTest2() throws CatalogDBException, IOException {
        String userId = user1.getId();
        Session sessionJCOLL = new Session("127.0.0.1");
        QueryResult<ObjectMap> login = catalogUserDBAdaptor.login(userId, "1234", sessionJCOLL);
        assertEquals(userId, login.first().getString("userId"));

        thrown.expect(CatalogDBException.class); //Already logged
        catalogUserDBAdaptor.login(userId, "1234", sessionJCOLL);
    }

    @Test
    public void logoutTest() throws CatalogDBException, IOException {
        String userId = user1.getId();
        Session sessionJCOLL = new Session("127.0.0.1");
        QueryResult<ObjectMap> login = catalogUserDBAdaptor.login(userId, "1234", sessionJCOLL);
        assertEquals(userId, login.first().getString("userId"));

        QueryResult logout = catalogUserDBAdaptor.logout(userId, sessionJCOLL.getId());
        assertEquals(0, logout.getResult().size());

        //thrown.expect(CatalogDBException.class);
        QueryResult falseSession = catalogUserDBAdaptor.logout(userId, "FalseSession");
        assertTrue(falseSession.getWarningMsg() != null && !falseSession.getWarningMsg().isEmpty());
    }

    @Test
    public void getUserIdBySessionId() throws CatalogDBException {
        String userId = user1.getId();

        catalogUserDBAdaptor.login(userId, "1234", new Session("127.0.0.1")); //Having multiple conections
        catalogUserDBAdaptor.login(userId, "1234", new Session("127.0.0.1"));
        catalogUserDBAdaptor.login(userId, "1234", new Session("127.0.0.1"));

        Session sessionJCOLL = new Session("127.0.0.1");
        QueryResult<ObjectMap> login = catalogUserDBAdaptor.login(userId, "1234", sessionJCOLL);
        assertEquals(userId, login.first().getString("userId"));

        assertEquals(user1.getId(), catalogUserDBAdaptor.getUserIdBySessionId(sessionJCOLL.getId()));
        QueryResult logout = catalogUserDBAdaptor.logout(userId, sessionJCOLL.getId());
        assertEquals(0, logout.getResult().size());

        assertEquals("", catalogUserDBAdaptor.getUserIdBySessionId(sessionJCOLL.getId()));
    }

    @Test
    public void changePasswordTest() throws CatalogDBException {
//        System.out.println(catalogUserDBAdaptor.changePassword("jmmut", "1111", "1234"));
//        System.out.println(catalogUserDBAdaptor.changePassword("jmmut", "1234", "1111"));
//        try {
//            System.out.println(catalogUserDBAdaptor.changePassword("jmmut", "BAD_PASSWORD", "asdf"));
//            fail("Expected \"bad password\" exception");
//        } catch (CatalogDBException e) {
//            System.out.println(e);
//        }
        QueryResult queryResult = catalogUserDBAdaptor.changePassword(user2.getId(), user2.getPassword(), "1234");
        assertNotSame(0, queryResult.getResult().size());

        thrown.expect(CatalogDBException.class);
        catalogUserDBAdaptor.changePassword(user2.getId(), "BAD_PASSWORD", "asdf");
    }

    @Test
    public void modifyUserTest() throws CatalogDBException {

        ObjectMap genomeMapsConfig = new ObjectMap("lastPosition", "4:1222222:1333333");
        genomeMapsConfig.put("otherConf", Arrays.asList(1, 2, 3, 4, 5));
        ObjectMap configs = new ObjectMap("genomemaps", genomeMapsConfig);
        ObjectMap objectMap = new ObjectMap("configs", configs.toJson());
        catalogUserDBAdaptor.update(user1.getId(), objectMap);

        User user = catalogUserDBAdaptor.getUser(user1.getId(), null, null).first();
        System.out.println(user);
    }


}
