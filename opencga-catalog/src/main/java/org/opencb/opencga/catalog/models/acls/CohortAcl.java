package org.opencb.opencga.catalog.models.acls;

import org.opencb.commons.datastore.core.ObjectMap;

import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by pfurio on 11/05/16.
 */
public class CohortAcl {

    private String member;
    private EnumSet<CohortPermissions> permissions;

    public enum CohortPermissions {
        VIEW,
        UPDATE,
        DELETE,
        SHARE,
        CREATE_ANNOTATIONS,
        VIEW_ANNOTATIONS,
        UPDATE_ANNOTATIONS,
        DELETE_ANNOTATIONS
    }

    public CohortAcl() {
    }

    public CohortAcl(String member, EnumSet<CohortPermissions> permissions) {
        this.member = member;
        this.permissions = permissions;
    }

    public CohortAcl(String member, ObjectMap permissions) {
        this.member = member;

        EnumSet<CohortPermissions> aux = EnumSet.allOf(CohortPermissions.class);
        for (CohortPermissions permission : aux) {
            if (permissions.containsKey(permission.name()) && permissions.getBoolean(permission.name())) {
                this.permissions.add(permission);
            }
        }
    }

    public CohortAcl(String member, List<String> permissions) {
        this.member = member;
        this.permissions = EnumSet.noneOf(CohortPermissions.class);
        if (permissions.size() > 0) {
            this.permissions.addAll(permissions.stream().map(CohortPermissions::valueOf).collect(Collectors.toList()));
        }
    }

    public String getMember() {
        return member;
    }

    public CohortAcl setMember(String member) {
        this.member = member;
        return this;
    }

    public EnumSet<CohortPermissions> getPermissions() {
        return permissions;
    }

    public CohortAcl setPermissions(EnumSet<CohortPermissions> permissions) {
        this.permissions = permissions;
        return this;
    }
}
