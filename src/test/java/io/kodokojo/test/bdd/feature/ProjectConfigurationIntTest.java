/**
 * Kodo Kojo - API frontend which dispatch REST event to Http services or publish event on EvetnBus.
 * Copyright © 2017 Kodo Kojo (infos@kodokojo.io)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package io.kodokojo.test.bdd.feature;


import com.tngtech.jgiven.annotation.As;
import com.tngtech.jgiven.junit.ScenarioTest;
import io.kodokojo.test.DockerIsRequire;
import io.kodokojo.test.DockerPresentMethodRule;
import io.kodokojo.test.bdd.API;
import io.kodokojo.test.bdd.stage.ApiGiven;
import io.kodokojo.test.bdd.stage.ApplicationThen;
import io.kodokojo.test.bdd.stage.ApplicationWhen;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;


@As("Project creation scenarios")
@API
@Ignore//  TODO: Must implement Mock eventBus for update project configuration.
public class ProjectConfigurationIntTest extends ScenarioTest<ApiGiven<?>, ApplicationWhen<?>, ApplicationThen<?>> {

    @Rule
    public DockerPresentMethodRule dockerPresentMethodRule = new DockerPresentMethodRule();

    @Test
    @DockerIsRequire
    public void create_a_simple_project_configuration() {
        given().api_is_ready(dockerPresentMethodRule.getDockerTestSupport())
                .and().i_am_user_$("jpthiery", true);
        when().create_a_new_default_project_configuration_with_name_$("Acme");
        then().it_exist_a_valid_project_configuration_in_store()
                .and().it_is_possible_to_get_complete_details_for_user_$("jpthiery");
    }

    @Test
    @DockerIsRequire
    public void create_a_project_configuration_with_only_Jenkins() {
        given().api_is_ready(dockerPresentMethodRule.getDockerTestSupport())
                .and().i_am_user_$("jpthiery", true);
        when().create_a_small_custom_project_configuration_with_name_$_and_only_brick_type_$("Acme", "jenkins");
        then().it_exist_a_valid_project_configuration_in_store()
                .and().it_is_possible_to_get_complete_details_for_user_$("jpthiery");
    }

    @Test
    @DockerIsRequire
    public void add_a_user_to_project_configuration() {
        given().api_is_ready(dockerPresentMethodRule.getDockerTestSupport())
                .and().i_am_user_$("jpthiery", true);
        when().create_a_new_default_project_configuration_with_name_$("Acme")
                .and().retrive_a_new_id()
                .and().create_user_with_email_$("aletaxin@kodokojo.io")
                .and().add_user_$_to_project_configuration("aletaxin");
        then().it_exist_a_valid_project_configuration_in_store_which_contain_user("aletaxin")
                .and().it_is_possible_to_get_details_for_user_$("aletaxin")
                .and().user_$_belong_to_entity_of_project_configuration("aletaxin")
                .and().it_is_possible_to_get_projectConfiguration_of_$_state_from_user_$("Acme", "aletaxin");
    }

    @Test
    @DockerIsRequire
    public void add_a_user_to_project_configuration_as_no_owner_will_fail() {
        given().api_is_ready(dockerPresentMethodRule.getDockerTestSupport())
                .and().i_am_user_$("jpthiery", true);
        when().create_a_new_default_project_configuration_with_name_$("Acme")
                .and().create_user_with_email_$("aletaxin@kodokojo.io");
        then().add_user_$_to_project_configuration_as_user_$_will_fail("aletaxin", "aletaxin");
    }

    @Test
    @DockerIsRequire
    public void remove_a_user_to_project_configuration() {
        given().api_is_ready(dockerPresentMethodRule.getDockerTestSupport())
                .and().i_am_user_$("jpthiery", true);
        when().create_a_new_default_project_configuration_with_name_$("Acme")
                .and().create_user_with_email_$("aletaxin@kodokojo.io")
                .and().add_user_$_to_project_configuration("aletaxin")
                .and().remove_user_$_to_project_configuration("aletaxin");
        then().it_exist_NOT_a_valid_project_configuration_in_store_which_contain_user("aletaxin");
    }
}
