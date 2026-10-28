package com.employeemonitor.api.rbac;

import com.employeemonitor.api.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RbacIntegrationTest extends IntegrationTestBase {

    @Test
    void hrAdminManagesEmployeesButItAdminCannot() throws Exception {
        String admin = adminToken();
        UserSession hr = createUserAndLogin(admin, Set.of("HR_ADMIN"), null);
        UserSession it = createUserAndLogin(admin, Set.of("IT_ADMIN"), null);

        String code = unique("HR");
        Map<String, Object> body = Map.of("employeeCode", code, "fullName", "Priya Sharma",
                "email", code.toLowerCase() + "@example.test");
        call(post("/api/employees"), hr.token(), body).andExpect(status().isCreated());

        String code2 = unique("IT");
        call(post("/api/employees"), it.token(), Map.of("employeeCode", code2, "fullName", "X",
                "email", code2.toLowerCase() + "@example.test")).andExpect(status().isForbidden());
    }

    @Test
    void managerSeesOnlyExplicitlyAssignedEmployees() throws Exception {
        String admin = adminToken();
        long assigned = createEmployee(admin, "Rahul Kumar");
        long other = createEmployee(admin, "Kiran Rao");
        UserSession manager = createUserAndLogin(admin, Set.of("MANAGER"), null);

        // Nothing visible by default.
        assertThat(getJson("/api/employees", manager.token()).get("totalElements").asLong()).isZero();
        call(get("/api/employees/" + assigned), manager.token(), null).andExpect(status().isNotFound());

        putJson("/api/users/" + manager.id() + "/assigned-employees/" + assigned, admin,
                Map.of("liveViewAllowed", false), 200);

        JsonNode list = getJson("/api/employees", manager.token());
        assertThat(list.get("totalElements").asLong()).isEqualTo(1);
        assertThat(list.at("/content/0/id").asLong()).isEqualTo(assigned);
        getJson("/api/employees/" + assigned, manager.token());
        call(get("/api/employees/" + other), manager.token(), null).andExpect(status().isNotFound());
    }

    @Test
    void assignmentRequiresManagerRole() throws Exception {
        String admin = adminToken();
        long employee = createEmployee(admin, "Someone");
        UserSession hr = createUserAndLogin(admin, Set.of("HR_ADMIN"), null);
        call(put("/api/users/" + hr.id() + "/assigned-employees/" + employee), admin, Map.of())
                .andExpect(status().isBadRequest());
    }

    @Test
    void employeeSeesOnlyThemselves() throws Exception {
        String admin = adminToken();
        long self = createEmployee(admin, "Self Person");
        long other = createEmployee(admin, "Other Person");
        UserSession employee = createUserAndLogin(admin, Set.of("EMPLOYEE"), self);

        JsonNode list = getJson("/api/employees", employee.token());
        assertThat(list.get("totalElements").asLong()).isEqualTo(1);
        getJson("/api/employees/" + self, employee.token());
        call(get("/api/employees/" + other), employee.token(), null).andExpect(status().isNotFound());

        JsonNode overview = getJson("/api/self", employee.token());
        assertThat(overview.at("/employee/id").asLong()).isEqualTo(self);
    }

    @Test
    void sensitiveAdminAreasAreRestricted() throws Exception {
        String admin = adminToken();
        UserSession manager = createUserAndLogin(admin, Set.of("MANAGER"), null);
        UserSession hr = createUserAndLogin(admin, Set.of("HR_ADMIN"), null);
        UserSession it = createUserAndLogin(admin, Set.of("IT_ADMIN"), null);

        call(get("/api/audit-logs"), manager.token(), null).andExpect(status().isForbidden());
        call(get("/api/audit-logs"), hr.token(), null).andExpect(status().isForbidden());
        getJson("/api/audit-logs", admin);

        call(get("/api/users"), it.token(), null).andExpect(status().isForbidden());
        call(get("/api/devices"), hr.token(), null).andExpect(status().isForbidden());
        getJson("/api/devices", it.token());

        call(post("/api/enrollment-tokens"), hr.token(), Map.of()).andExpect(status().isForbidden());
        call(post("/api/enrollment-tokens"), it.token(), Map.of()).andExpect(status().isCreated());

        call(put("/api/policies/default"), hr.token(), Map.of()).andExpect(status().isForbidden());
    }

    @Test
    void viewingAnEmployeeIsAudited() throws Exception {
        String admin = adminToken();
        long employee = createEmployee(admin, "Audited Person");
        getJson("/api/employees/" + employee, admin);

        JsonNode logs = getJson("/api/audit-logs?action=EMPLOYEE_VIEWED&targetType=EMPLOYEE&targetId=" + employee,
                admin);
        assertThat(logs.get("totalElements").asLong()).isEqualTo(1);
        assertThat(logs.at("/content/0/actorUsername").asText()).isEqualTo(ADMIN_USERNAME);
    }
}
