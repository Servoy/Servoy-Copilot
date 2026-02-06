# Database Tools Test Prompts

**Purpose:** Manual testing prompts for DatabaseTools (listTables, getTableInfo) with comprehensive coverage.

**Format:** Each prompt is numbered and describes a specific scenario to test.

**Prerequisites:**
- At least one database server configured (e.g., "example_data")
- At least one table with columns (e.g., "customers")
- Database server with valid connection

---

## Test Prompt 1: List All Tables in a Server
**Scenario:** List all tables available in a specific database server.

**Prompt:**
```
Show me all tables in the example_data database server
```

**Expected Result:**
- List of all tables in the server
- Table count displayed
- Clean formatted output with bullet points
- No errors

**Validation:**
- Check that known tables appear in the list
- Verify table count matches actual database

---

## Test Prompt 2: Get Table Info for Existing Table
**Scenario:** Retrieve detailed information about a specific table.

**Prompt:**
```
Give me full details for the customers table from example_data database
```

**Expected Result:**
- Table name and DataSource displayed
- Complete list of columns with:
  - Column number
  - Column name
  - Column type
  - Primary key indicator (true/false)
- All columns processed successfully
- No ERROR entries in column types

**Validation:**
- Verify all expected columns are listed
- Check primary key columns are correctly marked
- Ensure column types are meaningful (not "UNKNOWN" or "ERROR")

---

## Test Prompt 3: Get Table Info with Different Case
**Scenario:** Test case sensitivity handling for server and table names.

**Prompt:**
```
I need table information for CUSTOMERS from Example_Data server
```

**Expected Result:**
- Should handle case appropriately (depends on database server case sensitivity)
- Either returns table info or appropriate error message
- No crashes or exceptions

**Validation:**
- Verify system handles case correctly based on database configuration

---

## Test Prompt 4: List Tables from Non-Existent Server
**Scenario:** Error handling when server doesn't exist.

**Prompt:**
```
List all tables in the nonexistent_server database
```

**Expected Result:**
- Error message: "Database server 'nonexistent_server' not found"
- No stack traces visible to user
- Graceful error handling

**Validation:**
- Confirm error message is clear and helpful
- No application crashes

---

## Test Prompt 5: Get Info for Non-Existent Table
**Scenario:** Error handling when table doesn't exist in the server.

**Prompt:**
```
Show me details for the nonexistent_table from example_data
```

**Expected Result:**
- Error message: "Table 'nonexistent_table' not found in server 'example_data'"
- Graceful error handling
- No crashes

**Validation:**
- Error message is clear and indicates both table and server names

---

## Test Prompt 6: List Tables from Empty Server
**Scenario:** Handle server with no tables.

**Prompt:**
```
What tables are in the empty_server database?
```

**Expected Result:**
- Message indicating server was found
- "(No tables found)" or similar message
- No errors

**Validation:**
- Confirm empty result is handled gracefully

---

## Test Prompt 7: Get Table Info for Table Without Columns
**Scenario:** Handle edge case of table with no columns (rare but possible).

**Prompt:**
```
Get details for empty_table from example_data
```

**Expected Result:**
- Table name and DataSource displayed
- "(No columns found)" message
- Graceful handling

**Validation:**
- No crashes when table has no columns

---

## Test Prompt 8: Multiple Database Operations
**Scenario:** Test sequential database queries in one conversation.

**Prompt:**
```
First, show me all tables in example_data. Then give me details for the customers table.
```

**Expected Result:**
- First: List of all tables
- Second: Detailed info for customers table
- Both operations complete successfully
- Clear separation between results

**Validation:**
- Both tool calls execute correctly
- Results are distinct and properly formatted

---

## Test Prompt 9: Complex Table with Many Columns
**Scenario:** Test performance and formatting with large table.

**Prompt:**
```
Show me complete information for the orders table in example_data (if it has 20+ columns)
```

**Expected Result:**
- All columns listed correctly
- No truncation
- Proper numbering (1, 2, 3...)
- All column types retrieved
- Primary keys identified

**Validation:**
- Verify all columns are present
- No missing data mid-list
- Performance is acceptable

---

## Test Prompt 10: Table with Composite Primary Key
**Scenario:** Test handling of tables with multiple primary key columns.

**Prompt:**
```
Get table details for order_items from example_data (if it has composite PK: order_id + item_id)
```

**Expected Result:**
- All primary key columns marked as "Primary Key: true"
- Non-PK columns marked as "Primary Key: false"
- Correct identification of all PK columns

**Validation:**
- Verify all PK columns are correctly identified
- Non-PK columns are not incorrectly marked

---

## Test Prompt 11: Natural Language Variation 1
**Scenario:** Test natural language understanding.

**Prompt:**
```
What are the columns in the products table?
```

**Expected Result:**
- Recognizes request for table information
- Returns column details for products table
- Uses default/active database server or asks for clarification

**Validation:**
- Tool is called correctly
- Natural language is interpreted properly

---

## Test Prompt 12: Natural Language Variation 2
**Scenario:** Test alternative phrasing.

**Prompt:**
```
Can you tell me about the structure of the customers table in example_data?
```

**Expected Result:**
- Returns detailed table information
- Natural language processed correctly

**Validation:**
- Appropriate tool called (getTableInfo)
- Correct parameters extracted

---

## Test Prompt 13: Database Schema Exploration Workflow
**Scenario:** Realistic workflow exploring database schema.

**Prompt:**
```
I want to understand the example_data database. First show me what tables exist, then give me details on the customers and orders tables.
```

**Expected Result:**
1. List of tables from example_data
2. Detailed info for customers table
3. Detailed info for orders table
- Clear workflow execution
- Helpful information for database exploration

**Validation:**
- All three operations complete successfully
- Information is useful for understanding database schema

---

## Test Prompt 14: Table Info for Analysis
**Scenario:** Request table info for development planning.

**Prompt:**
```
I need to create a form for the customers table. What columns does it have and which are primary keys?
```

**Expected Result:**
- Table information retrieved
- Column list with types
- Primary keys clearly identified
- AI provides helpful context about using this for form creation

**Validation:**
- getTableInfo tool called correctly
- Response is helpful for the stated goal

---

## Test Prompt 15: Special Characters in Names
**Scenario:** Handle table/server names with special characters (if applicable).

**Prompt:**
```
Show tables in my_database_2024 server
```

**Expected Result:**
- Handles underscore and numeric characters correctly
- Returns table list or appropriate error

**Validation:**
- Special characters don't cause parsing issues

---

## Test Prompt 16: Error Recovery
**Scenario:** Test error recovery and retry.

**Prompt:**
```
Get info for customers from wrong_server. Oh wait, I meant example_data.
```

**Expected Result:**
- First attempt: Error for wrong_server
- Second attempt: Success with example_data
- Conversation continues normally after error

**Validation:**
- Errors don't break the conversation flow
- Retry works correctly

---

## Test Prompt 17: Table Info with Foreign Keys
**Scenario:** View table that has foreign key relationships.

**Prompt:**
```
Show me the order_details table structure from example_data
```

**Expected Result:**
- All columns listed
- Foreign key columns shown (no special FK indicator currently, just column name/type)
- Primary keys correctly identified

**Validation:**
- FK columns appear in the list
- No errors when FK relationships exist

---

## Test Prompt 18: Mixed Case Table Name
**Scenario:** Test handling of mixed case in table names.

**Prompt:**
```
Get details for CustomerData table in example_data
```

**Expected Result:**
- Depends on database case sensitivity
- Either finds table or returns clear "not found" message
- No crashes

**Validation:**
- Case handling is consistent with database behavior

---

## Test Prompt 19: Performance Test
**Scenario:** Test with server containing many tables (50+).

**Prompt:**
```
List all tables in large_database server
```

**Expected Result:**
- All tables listed
- Acceptable performance (< 5 seconds)
- No truncation or pagination issues
- Complete list returned

**Validation:**
- Verify table count matches database
- No timeout or memory issues

---

## Test Prompt 20: Column Type Variety
**Scenario:** View table with diverse column types.

**Prompt:**
```
Show me the media_library table structure from example_data (table with TEXT, INTEGER, DATETIME, MEDIA types)
```

**Expected Result:**
- All column types correctly identified and displayed
- Each type shown as meaningful name (not "UNKNOWN")
- Types match Servoy's type system

**Validation:**
- Verify types are accurate
- No "UNKNOWN" for standard types
- Special types (MEDIA) handled correctly

---

## Debug Mode Testing

**Enable debug mode with:** `-Dconsole.debug=true`

### Debug Test 1: Verify Logging

**Prompt:**
```
List tables in example_data
```

**Expected Debug Output:**
```
ServoyPilot-DEBUG [DatabaseTools.listTables] ENTRY - Params: example_data'
ServoyPilot-DEBUG [DatabaseTools] Found X tables in example_data
ServoyPilot-DEBUG [DatabaseTools.listTables] EXIT - Return: X tables
```

---

### Debug Test 2: Verify Error Logging

**Prompt:**
```
Get info for nonexistent from bad_server
```

**Expected Debug Output:**
```
ServoyPilot-DEBUG [DatabaseTools.getTableInfo] ENTRY - Params: bad_server, nonexistent
ServoyPilot-DEBUG [DatabaseTools] Server not found: bad_server
```

---

### Debug Test 3: Verify Column Processing

**Prompt:**
```
Show customers table details from example_data
```

**Expected Debug Output:**
```
ServoyPilot-DEBUG [DatabaseTools.getTableInfo] ENTRY - Params: example_data, customers
ServoyPilot-DEBUG [DatabaseTools] Table found: customers, DataSource: db:/example_data/customers
ServoyPilot-DEBUG [DatabaseTools] Found X columns
ServoyPilot-DEBUG [DatabaseTools] Primary keys: [...]
ServoyPilot-DEBUG [DatabaseTools] Completed processing X columns
ServoyPilot-DEBUG [DatabaseTools.getTableInfo] EXIT - Return: Success - X columns
```

---

## Performance Benchmarks

| Operation | Typical Duration | Max Acceptable |
|-----------|------------------|----------------|
| List 10 tables | < 1 second | 3 seconds |
| List 100 tables | < 2 seconds | 5 seconds |
| Get table info (10 cols) | < 1 second | 3 seconds |
| Get table info (50 cols) | < 2 seconds | 5 seconds |

---

## Common Issues to Watch For

1. **Column Type Errors**: If you see "ERROR: ..." in column types, there's an issue with `getColumnType()`
2. **Incomplete Column Lists**: If column count doesn't match reality, check loop error handling
3. **Case Sensitivity**: Different databases handle case differently
4. **Special Characters**: Verify names with spaces, quotes, or special chars work
5. **Empty Results**: Distinguish between "no tables" vs "server not found"

---

## Test Coverage Checklist

- [ ] List tables from valid server
- [ ] List tables from non-existent server
- [ ] List tables from empty server
- [ ] Get info for valid table
- [ ] Get info for non-existent table
- [ ] Get info for table without columns
- [ ] Handle composite primary keys
- [ ] Handle tables with 20+ columns
- [ ] Handle various column types (TEXT, INTEGER, DATETIME, MEDIA)
- [ ] Natural language variations
- [ ] Sequential operations in one conversation
- [ ] Error recovery and retry
- [ ] Mixed case handling
- [ ] Special characters in names
- [ ] Debug logging verification
- [ ] Performance with large datasets

---

**Last Updated:** February 5, 2026  
**Total Test Prompts:** 20 + 3 Debug Tests  
**Coverage:** listTables, getTableInfo, error handling, edge cases, performance
