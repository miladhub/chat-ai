# chat-ai

Example of using a conversational AI with embeddings with Java.

This project uses
* [OpenAI APIs](https://platform.openai.com/docs/api-reference) to interact with ChatGPT
* <https://github.com/pgvector/pgvector> as a vector database for embeddings

# Setting up the database

Install <https://www.postgresql.org> and <https://github.com/pgvector/pgvector> on
top of it; as an example, here's how to do it on macOS:

```shell
$ brew install postgresql
$ brew services start postgresql@14
$ brew install pgvector
```

Create (or reset) the database user and schema:

```shell
$ psql postgres -a -f recreate_db_and_user.sql
```

Install the extension on the schema by connecting as the superuser:

```shell
$ psql chat -c "CREATE EXTENSION vector"
```

Create (or reset) the tables:

```shell
$ psql chat -U chat -a -w -f recreate_tables.sql
```

To delete all past interactions, contexts and function, execute the last command again.

# Building

The project comprises a main module, that includes a command-line interface,
and a web module, that offers a REST interface. The following commands builds everything:

```shell
$ mvn clean install
```

# Asking for completions

Completions can be asked from both the CLI and from the REST interface.

To use the CLI, define the `OPENAI_API_KEY` environment variable and run the CLI:

```shell
$ export OPENAI_API_KEY=your-api-key
$ ./cli.sh
Hit Ctrl-D to exit.
To save or update context entries, type:
:context (save|delete) <entry-name> [<entry-value>]
To save or update functions, type:
:function (save|delete) <fn-name> [<fn-descr> <fn-params-json-schema-file>]
Enjoy!
> Hello!
Model> Hello! How can I assist you today?
```

To use the REST interface, first start the server:

```shell
$ ./rest.sh
```

and then use it:

```shell
$ curl -s localhost:8080/chat \
  -H "Accept: application/json" \
  -H "Content-type: application/json" \
  -d '{
    "apiKey": "your-api-key",
    "prompt": "Hello!"
  }' | jq
{
  "content" : "Hello! How can I assist you today?"
}
```

Or, using Javascript:

```javascript
fetch("http://localhost:8080/chat", {
    method: "POST",
    headers: {
        "Accept": "application/json",
        "Content-Type": "application/json"
    },
    body: JSON.stringify({
        "apiKey": "your-api-key",
        "prompt": "Hello!"
    })
})
.then(res => res.json())
.then(console.log)
```

# Using contexts

Context entries are sent at every interaction, with the role "system", to help the AI
perform better.

Contexts can only be managed from the CLI. For example:

```shell
> :context save role You are my Dungeons and Dragons master, and I play the character Duncan, a level 20 wizard
Model> context updated.
> who am I?
Model> As the Dungeon Master, it is up to you to create and control the world and its inhabitants.
You have the power to shape the story and guide the players through their adventures.
Your role is to describe the environments, non-player characters, and to facilitate the progression of the game.
So, in this case, you are the one orchestrating the game and playing all the other characters besides Duncan.
> 
```

# Using functions

[Functions](https://platform.openai.com/docs/guides/gpt/function-calling) are sent at every interaction, to allow the
caller an executable piece of logic as the AI response.

Functions can only be managed from the CLI.

```shell
> :function save update_characters_stats update_characters_stats.json
Model> function updated.
> make it so that Duncan gets a bit hurt
Model> update_characters_stats( {
  "changes": [
    {
      "stat": "hp",
      "delta": -5,
      "character": "Duncan"
    },
    {
      "stat": "stamina",
      "delta": -2,
      "character": "Duncan"
    }
  ]
} )
> 
```

When a function response is received from the CLI, the textual chat just prints out how the
function would be executed in a pseudo language. To actually be able to use the function, it is
better to ask for completions via REST, so that the client (e.g., a Javascript one) can actually
use the response to invoke a function or perform some logic.

# Running the DB elsewhere

This guide assumes that PostgreSQL will be running locally, define the following
environment variables to override its location:

```shell
$ export PG_URL=jdbc:postgresql://localhost:5432/chat
$ export PG_USER=chat
$ export PG_PSW=chat
```
