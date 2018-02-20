/*
 * (C) Copyright 2018 Nuxeo (http://nuxeo.com/) and others.
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
 *
 * Contributors:
 *     Gethin James
 */
package org.nuxeo.ecm.platform.audit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.nuxeo.ecm.platform.audit.api.BuiltinLogEntryData.LOG_DOC_PATH;
import static org.nuxeo.ecm.platform.audit.api.BuiltinLogEntryData.LOG_EVENT_ID;
import static org.nuxeo.ecm.platform.audit.api.BuiltinLogEntryData.LOG_ID;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.junit.Test;
import org.nuxeo.ecm.core.api.ScrollResult;
import org.nuxeo.ecm.core.query.sql.model.Literals;
import org.nuxeo.ecm.core.query.sql.model.Operator;
import org.nuxeo.ecm.core.query.sql.model.Predicate;
import org.nuxeo.ecm.core.query.sql.model.Reference;
import org.nuxeo.ecm.core.uidgen.UIDGeneratorService;
import org.nuxeo.ecm.core.uidgen.UIDSequencer;
import org.nuxeo.ecm.platform.audit.api.AuditQueryBuilder;
import org.nuxeo.ecm.platform.audit.api.LogEntry;
import org.nuxeo.ecm.platform.audit.api.Predicates;
import org.nuxeo.ecm.platform.audit.impl.LogEntryImpl;
import org.nuxeo.ecm.platform.audit.service.AbstractAuditBackend;
import org.nuxeo.ecm.platform.audit.service.AuditBackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Audit test that are common across all storage backends
 */
public abstract class AbstractAuditStorageTest {

    public static final String ID_FOR_AUDIT_STORAGE_TESTS = "ID_FOR_AUDIT_STORAGE_TESTS";

    public static final String SEQ_NAME = "test-audit-seq";

    public static final int NUM_OF_EVENTS = 56;

    @Inject
    protected AuditBackend auditBackend;

    @Inject
    protected UIDGeneratorService uidGeneratorService;

    protected ObjectMapper mapper = new ObjectMapper();

    protected List<Long> ids = new ArrayList<>();

    /**
     * Sets up data for the tests below. Doesn't run using @Before so as not to interfere with other audit backends
     */
    public void setUpTestData() throws Exception {

        AbstractAuditBackend backend = (AbstractAuditBackend) this.auditBackend;
        UIDSequencer seq = uidGeneratorService.getSequencer();
        AuditQueryBuilder builder = new AuditQueryBuilder().predicates(
                Predicates.eq(LOG_EVENT_ID, ID_FOR_AUDIT_STORAGE_TESTS));
        List<LogEntry> logs = backend.queryLogs(builder);

        if (logs.isEmpty()) {

            List<String> jsonEntries = new ArrayList<>();

            for (int i = 1; i <= NUM_OF_EVENTS; i++) {
                ObjectNode logEntryJson = mapper.createObjectNode();
                long eventId = seq.getNextLong(SEQ_NAME);
                logEntryJson.put(LOG_ID, eventId);
                logEntryJson.put(LOG_EVENT_ID, ID_FOR_AUDIT_STORAGE_TESTS);
                logEntryJson.put(LOG_DOC_PATH, i % 2 == 0 ? "/is/even" : "/is/odd");
                jsonEntries.add(mapper.writeValueAsString(logEntryJson));
                ids.add(eventId);
            }

            backend.append(jsonEntries);
            flush();
        }
    }

    protected abstract void flush() throws Exception;

    @Test
    public void testSaveAndScroll() throws Exception {
        setUpTestData();
        AbstractAuditBackend backend = (AbstractAuditBackend) this.auditBackend;

        AuditQueryBuilder builder = new AuditQueryBuilder().predicates(
                Predicates.eq(LOG_EVENT_ID, ID_FOR_AUDIT_STORAGE_TESTS));
        List<LogEntry> logs = backend.queryLogs(builder);
        assertEquals(ids.size(), logs.size());

        ScrollResult<String> scrollResult = backend.scroll(builder, 5, 10);
        int total = 0;
        while (scrollResult.hasResults()) {
            assertTrue(scrollResult.getResults().size() <= 5);
            List<String> jsonEntries = scrollResult.getResults();
            List<LogEntry> entries = jsonEntries.stream().map(json -> {
                try {
                    return mapper.readValue(json, LogEntryImpl.class);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).collect(Collectors.toList());
            for (LogEntry entry : entries) {
                assertTrue(ids.contains(entry.getId()));
                assertEquals(ID_FOR_AUDIT_STORAGE_TESTS, entry.getEventId());
            }
            total += entries.size();
            scrollResult = backend.scroll(scrollResult.getScrollId());
        }
        assertEquals(ids.size(), total);

        LogEntry logEntry = backend.getLogEntryByID(ids.get(4).longValue());
        assertNotNull(logEntry);
        assertEquals(ids.get(4).longValue(), logEntry.getId());
        assertEquals(ID_FOR_AUDIT_STORAGE_TESTS, logEntry.getEventId());
    }

    @Test
    public void testStartsWith() throws Exception {
        setUpTestData();
        assertStartsWithCount(NUM_OF_EVENTS, "/");
        assertStartsWithCount(NUM_OF_EVENTS, "/is");
        assertStartsWithCount(NUM_OF_EVENTS, "/is/");
        assertStartsWithCount(NUM_OF_EVENTS / 2, "/is/even");
        assertStartsWithCount(NUM_OF_EVENTS / 2, "/is/odd");
    }

    /**
     * Asserts the number of events that match the startsWith parameter
     */
    public void assertStartsWithCount(int eventsCount, String startsWith) {
        List<LogEntry> logs = auditBackend.queryLogs(new AuditQueryBuilder().predicates(
                Predicates.eq(LOG_EVENT_ID, ID_FOR_AUDIT_STORAGE_TESTS),
                new Predicate(new Reference(LOG_DOC_PATH), Operator.STARTSWITH, Literals.toLiteral(startsWith))));
        assertEquals(eventsCount, logs.size());
    }
}
