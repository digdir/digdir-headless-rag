# Q56 v1 — When unit testing an Altinn Studio React component, how do I stop the real API call from firing and make it return fake data instead?

**Grounded source**: `bcefb75d51b5` (Unit testing, community). **Register**: developer — practical phrasing ("stop the real API call", "return fake data"); corpus answers with Jest spyOn/mockImplementation on the shared `networking` module.

## Goldens (read-confirmed)
- `e5a7a7aef360` — core; how to mock get/post/put from the shared networking module with jest.spyOn + mockImplementation and resolve the promise.
- `e3b6a7663094` — core; mocking a rejected promise / error case and spying on console.error.
- `f1f67ab17159` — supporting; need to mock networked functions before mounting when called in componentDidMount, and spyOn usage.

## Cited chunks

`e5a7a7aef360` `e3b6a7663094` `f1f67ab17159`
