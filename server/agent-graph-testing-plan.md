# Agent Graph Node Testing Plan

## Overview
This document outlines the incremental testing plan for agent graph node types. We'll start with single nodes, then test them inside containers, tables, and llm-foreach structures.

## Testing Approach
1. Create test graph files (.clj)
2. Load in web app and verify:
   - Processing completion
   - View output rendering
   - Server logs
   - Proper child handling

## Phase 1: Single Node Tests

### 1.1 Basic Nodes
- [ ] `test-01-static-text.clj` - Simple static text node
- [ ] `test-02-value.clj` - Basic value node
- [ ] `test-03-first.clj` - First node with multiple children

### 1.2 LLM Nodes  
- [ ] `test-04-llm-simple.clj` - Basic LLM node with question
- [ ] `test-05-llm-with-context.clj` - LLM with child context
- [ ] `test-06-llm-structured.clj` - Structured LLM with schema

### 1.3 Data Source Nodes
- [ ] `test-07-web-simple.clj` - Single web fetch
- [ ] `test-08-typesense-basic.clj` - Basic Typesense query

## Phase 2: Container Tests

### 2.1 Simple Container
- [ ] `test-09-container-empty.clj` - Empty container
- [ ] `test-10-container-static.clj` - Container with static-text children
- [ ] `test-11-container-mixed.clj` - Container with mixed node types

### 2.2 Container with Processing Nodes
- [ ] `test-12-container-llm.clj` - Container with LLM nodes
- [ ] `test-13-container-web.clj` - Container with web fetches
- [ ] `test-14-container-nested.clj` - Nested containers

## Phase 3: Table Tests

### 3.1 Basic Table
- [ ] `test-15-table-static.clj` - Table with static data
- [ ] `test-16-table-schema.clj` - Table with defined schema
- [ ] `test-17-table-from-llm.clj` - Table populated by LLM

### 3.2 Table with Children
- [ ] `test-18-table-with-typesense.clj` - Table with Typesense data
- [ ] `test-19-table-with-web.clj` - Table with web sources

## Phase 4: LLM-Foreach Tests

### 4.1 Basic Iteration
- [ ] `test-20-foreach-static.clj` - Foreach over static items
- [ ] `test-21-foreach-limit.clj` - Foreach with iteration limit
- [ ] `test-22-foreach-context.clj` - Foreach with named context

### 4.2 Complex Foreach
- [ ] `test-23-foreach-table-input.clj` - Foreach processing table rows
- [ ] `test-24-foreach-nested.clj` - Nested foreach structures
- [ ] `test-25-foreach-mixed.clj` - Foreach with mixed child types

## Unit Test Requirements

Based on manual testing, create unit tests for:

### Core Functionality
- [ ] Node type dispatch (process-node multimethod)
- [ ] Async processing with result atoms
- [ ] Child node aggregation
- [ ] Named children context passing

### Node-Specific Tests
- [ ] Static-text: Direct answer pass-through
- [ ] Value: Value extraction and formatting
- [ ] First: Selection logic and filtering
- [ ] LLM: Async call handling and response parsing
- [ ] LLM-Structured: Schema validation and structuring
- [ ] LLM-Foreach: Iteration and context injection
- [ ] Web: URL fetching and error handling
- [ ] Typesense: Query building and result parsing
- [ ] Container: Child aggregation without processing
- [ ] Table: Row generation and schema compliance

### UI Component Tests
- [ ] Answer rendering for each node type
- [ ] Collapsible sections and tree navigation
- [ ] Export functionality (CSV/JSON for tables)
- [ ] Error state displays
- [ ] Loading indicators

### Integration Tests
- [ ] Full graph processing (root to leaves)
- [ ] Concurrent node processing
- [ ] Error propagation
- [ ] Timeout handling
- [ ] Memory usage with large graphs

## Test Data Requirements

### Mock Data
- Static text responses
- Sample web content
- Typesense result sets
- LLM response templates

### Test Endpoints
- Local test server for web nodes
- Mock Typesense instance
- LLM stub service

## Success Metrics
- All nodes complete processing within timeout
- UI renders correctly for all node types
- No memory leaks with repeated runs
- Error states handled gracefully
- Child data properly aggregated