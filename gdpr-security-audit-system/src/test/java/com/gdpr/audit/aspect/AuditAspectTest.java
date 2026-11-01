package com.gdpr.audit.aspect;

import com.gdpr.audit.dto.UserDTO;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class AuditAspectTest {

    @Test
    void extractsNumericPathIdAsResourceId() {
        assertThat(AuditAspect.extractResourceId(new Object[]{42L, new Object()}, null)).isEqualTo("42");
    }

    @Test
    void returnsNullWhenMethodHasNoScalarResourceId() {
        assertThat(AuditAspect.extractResourceId(new Object[]{new Object(), null}, null)).isNull();
        assertThat(AuditAspect.extractResourceId(null, null)).isNull();
    }

    @Test
    void extractsCreatedResourceIdFromResponseBody() {
        UserDTO created = UserDTO.builder().id(73L).build();
        assertThat(AuditAspect.extractResourceId(new Object[]{created}, ResponseEntity.ok(created)))
                .isEqualTo("73");
    }
}
