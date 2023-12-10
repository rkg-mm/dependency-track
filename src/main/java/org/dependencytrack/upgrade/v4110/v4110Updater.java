/*
 * This file is part of Dependency-Track.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) Steve Springett. All Rights Reserved.
 */
package org.dependencytrack.upgrade.v4110;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import org.dependencytrack.model.Severity;
import org.dependencytrack.util.VulnerabilityUtil;

import alpine.common.logging.Logger;
import alpine.persistence.AlpineQueryManager;
import alpine.server.upgrade.AbstractUpgradeItem;


public class v4110Updater extends AbstractUpgradeItem {

    private static final Logger LOGGER = Logger.getLogger(v4110Updater.class);

    @Override
    public String getSchemaVersion() {
        return "4.11.0";
    }

    @Override
    public void executeUpgrade(final AlpineQueryManager qm, final Connection connection) throws Exception {
        dropCweTable(connection);
        setCalculatedSeverities(connection);
    }

    private static void dropCweTable(final Connection connection) throws Exception {
        LOGGER.info("Dropping CWE table");
        try (final Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE \"CWE\"");
        }
    }

    private static void setCalculatedSeverities(final Connection connection) throws Exception {
        // Part of a fix for https://github.com/DependencyTrack/dependency-track/issues/2474
        // recomputes all database severity values with value NULL of a vulnerability and updates them in the database
        LOGGER.info("Updating all null severities from database");
        try (final Statement stmt = connection.createStatement()) {
            final ResultSet rs = stmt.executeQuery("""
                    SELECT CVSSV2BASESCORE, CVSSV3BASESCORE, OWASPRRLIKELIHOODSCORE, OWASPRRTECHNICALIMPACTSCORE, OWASPRRBUSINESSIMPACTSCORE, VULNID
                    FROM "VULNERABILITY"
                    WHERE "SEVERITY" is NULL
                    """);
            while(rs.next()){
                String vulnID = rs.getString(6);
                Severity severity = VulnerabilityUtil.getSeverity(
                        rs.getBigDecimal(1),
                        rs.getBigDecimal(2),
                        rs.getBigDecimal(3),
                        rs.getBigDecimal(4),
                        rs.getBigDecimal(5)
                );
                final String severityString = severity.name();
                final PreparedStatement ps = connection.prepareStatement("""
                        UPDATE "VULNERABILITY" SET "SEVERITY" = ? WHERE "VULNID" = ?
                        """);

                ps.setString(1, severityString);
                ps.setString(2, vulnID);
                ps.executeUpdate();
            }
        }
    }
}