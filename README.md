A quick example of how to use a "RequestContextService"/FiberRef to add
contextual info into a chain of Middleware, than can then be used in the
endpoint logic.

Try with

curl -v -u me:mypass http://localhost:9000

(and again with bad/no creds)

