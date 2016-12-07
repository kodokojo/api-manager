/**
 * Kodo Kojo - Software factory done right
 * Copyright © 2016 Kodo Kojo (infos@kodokojo.io)
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package io.kodokojo.test.bdd.feature;


import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.junit.ScenarioTest;
import io.kodokojo.test.DockerIsRequire;
import io.kodokojo.test.DockerPresentMethodRule;
import io.kodokojo.test.bdd.User;
import io.kodokojo.test.bdd.stage.ApiGiven;
import io.kodokojo.test.bdd.stage.ApplicationThen;
import io.kodokojo.test.bdd.stage.ApplicationWhen;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;


@As("User creation scenarii")
@User
public class UserIntTest extends ScenarioTest<ApiGiven<?>, ApplicationWhen<?>, ApplicationThen<?>> {

    @Rule
    public DockerPresentMethodRule dockerPresentMethodRule = new DockerPresentMethodRule();

    @Test
    @DockerIsRequire
    public void create_a_simple_user() {
        given().api_is_ready(dockerPresentMethodRule.getDockerTestSupport());
        when().retrive_a_new_id()
                .and().create_user_with_email_$("jpthiery@xebia.fr");
        then().it_exist_a_valid_user_with_username_$("jpthiery")
                .and().it_is_possible_to_get_complete_details_for_user_$("jpthiery")
                .and().user_$_belong_to_entity_$("jpthiery", "jpthiery@xebia.fr");
    }


    @Test
    @DockerIsRequire
    public void create_two_users_with_same_identifier() {
        given().api_is_ready(dockerPresentMethodRule.getDockerTestSupport());
        when().retrive_a_new_id()
                .and().create_user_with_email_$("jpthiery@xebia.fr")
                .and().create_user_with_email_$_which_must_fail("aletaxin@xebia.fr");

        then().it_exist_a_valid_user_with_username_$("jpthiery")
                .and().it_NOT_exist_a_valid_user_with_username_$("aletaxin");
    }

    @Test
    @DockerIsRequire
    public void create_two_users_and_get_details() {
        given().api_is_ready(dockerPresentMethodRule.getDockerTestSupport())
                .and().i_will_be_user_$("jpthiery");
        when().retrive_a_new_id()
                .and().create_user_with_email_$("jpthiery@xebia.fr")
                .and().retrive_a_new_id()
                .and().create_user_with_email_$("aletaxin@xebia.fr");
        then().it_exist_a_valid_user_with_username_$("jpthiery")
                .and().it_exist_a_valid_user_with_username_$("aletaxin")
                .and().it_is_possible_to_get_complete_details_for_user_$("jpthiery")
                .and().it_is_possible_to_get_details_for_user_$("aletaxin")
                .and().it_is_NOT_possible_to_get_complete_details_for_user_$("aletaxin");
    }

    @Test
    @DockerIsRequire
    public void create_two_users_with_same_username() {
        given().api_is_ready(dockerPresentMethodRule.getDockerTestSupport());
        when().retrive_a_new_id()
                .and().create_user_with_email_$("jpthiery@xebia.fr")
                .and().retrive_a_new_id()
                .and().create_user_with_email_$_which_must_fail("jpthiery@kodokojo.io");

        then().it_exist_a_valid_user_with_username_$("jpthiery");
    }

    @Test
    @DockerIsRequire
    public void try_to_create_user_without_identifier() {
        given().api_is_ready(dockerPresentMethodRule.getDockerTestSupport());
        when().create_user_with_email_$_which_must_fail("aletaxin@xebia.fr");

        then().it_NOT_exist_a_valid_user_with_username_$("aletaxin");
    }

    @Test
    @DockerIsRequire
    public void route_creation_user_waiting_list() {
        given().api_is_ready(dockerPresentMethodRule.getDockerTestSupport())
                .and().waiting_list_is_activated();
        when().retrive_a_new_id()
                .and().create_user_with_email_$("jpthiery@xebia.fr");
        then().it_NOT_exist_a_valid_user_with_username_$("jpthiery");
    }

    @Test
    @DockerIsRequire
    @Ignore
    public void create_a_simple_user_and_modify_password() {
        given().api_is_ready(dockerPresentMethodRule.getDockerTestSupport());
        when().retrive_a_new_id()
                .and().create_user_with_email_$("jpthiery@xebia.fr")
                .and().update_user_$_with_password_$("jpthiery", "mypassword", false);
        then().it_exist_a_valid_user_with_username_$("jpthiery")
                .and().it_is_possible_to_get_complete_details_for_user_$("jpthiery")
                .and().user_$_belong_to_entity_$("jpthiery", "jpthiery@xebia.fr");
    }

}
