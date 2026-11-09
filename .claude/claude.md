# Claude Project Instructions: Production-Ready Java

## 🏗️ Architecture & Framework Rules (Spring Boot / Enterprise)
- **Layered Responsibility:** Keep controllers thin. Put core business logic inside `@Service` classes. Use repositories *only* for persistence operations.
- **Data Boundaries:** Never expose JPA/Hibernate entities directly from Controllers. Map them to immutable DTOs (Data Transfer Objects) at the service boundary.
- **Dependency Injection:** Always use constructor-based dependency injection. Do not use `@Autowired` field injection.
- **External API Safety:** Implement strict validation, timeouts, retries, and circuit breakers for all downstream HTTP calls. Keep database transactions as short as possible.

## 💻 Java 21+ Modern Syntax & Style
- **Code Style:** Follow the Google Java Style Guide (PascalCase types, camelCase methods/variables, spaces over tabs).
- **Type Inference:** Use the `var` keyword for local variables where the concrete type is obvious from the right-hand assignment.
- **Immutability First:** Prefer Java `record` classes over standard classes with Lombok getters/setters for data holders and DTOs.
- **String Handling:** Use modern text blocks (`"""`) with `.formatted()` instead of heavy string concatenation.
- **Modern Collections:** Use `List.of()`, `Set.of()`, and `Map.of()` to initialize collections. Avoid legacy constructs like `Arrays.asList()`.
- **Stream Idioms:** Prefer Java Streams and advanced `switch` expressions (pattern matching on types/enums) over deep nested `if-else` blocks.

## 🛡️ Robustness & Security
- **Resource Management:** Enforce strict resource management using explicit `try-with-resources` blocks for streams, files, and sockets.
- **Validation:** Always validate incoming payloads at the API level (e.g., using `jakarta.validation` annotations like `@NotNull`, `@Size`).
- **Zero Secrets:** Never hardcode keys, credentials, or profile-specific configs. Fetch them via Spring `@Value` environment maps or secret managers.
- **No Speculative Handling:** Catch specific checked exceptions. Never catch generic `Exception` or `Throwable` unless implementing a global fallback filter.

## 🧪 Testing Requirements
- **Verification Loop:** Run your tests (`mvn test` or `./gradlew test`) and lint checks before providing a final response or marking a task complete.
- **Meaningful Assertions:** Do not generate "mock-everything" tests that test the mock rather than behavior. Write functional, high-value unit and slice tests (`@DataJpaTest`, `@WebMvcTest`).
- **Test Integrity:** Write test cases covering normal, negative, boundary, and zero values. Do not alter or break existing tests unless explicitly requested.

## 📍 Checkpointing
- **Start Here:** Read `docs/CHECKPOINT.md` before starting work. It records the current state, decisions, working agreement and next steps.
- **Keep It Current:** Update `docs/CHECKPOINT.md` at the end of every chunk of work, before reporting back, so another agent could continue from it alone.
