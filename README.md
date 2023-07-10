# chat-ai

Example of using a conversational AI with embeddings with Java.

This project uses
* [OpenAI APIs](https://platform.openai.com/docs/api-reference) to interact with ChatGPT
* <https://github.com/pgvector/pgvector> as a vector database for embeddings

# Setting up the database

Install <https://www.postgresql.org> and <https://github.com/pgvector/pgvector> on
top of it; as an example, here's how to do it on MacOS:

```shell
$ brew install postgresql
$ brew services start postgresql@14
$ brew install pgvector
```

Create the database user and schema:

```shell
$ psql postgres
psql (14.8 (Homebrew))
Type "help" for help.

postgres=# CREATE USER quests WITH PASSWORD 'quests';
postgres=# CREATE DATABASE quests OWNER quests;
postgres=# EXIT
```

Install the extension on the schema by connecting as the superuser:

```shell
$ psql quests
psql (14.8 (Homebrew))
Type "help" for help.

quests=# CREATE EXTENSION vector;
CREATE EXTENSION
```

The project expects a table called `prompts`, here's how to create it:

```shell
$ psql quests quests
psql (14.8 (Homebrew))
Type "help" for help.

quests=> create table prompts (
  id bigserial PRIMARY KEY,
  embedding vector(1536),
  role varchar(10),
  prompt varchar(4000)
);
```

To delete all past interactions:

```shell
$ psql quests quests
psql (14.8 (Homebrew))
Type "help" for help.

quests=> delete from prompts;
```

# Building

```shell
$ mvn clean install
```

# Running

Define the `OPENAI_API_KEY` environment variable and run the JAR:

```shell
$ export OPENAI_API_KEY=your-api-key
$ java -jar target/chat-ai-*-jar-with-dependencies.jar
Hit Ctrl-D to exit, enjoy!
> 
```

# Running the DB elsewhere

This guide assumes that PostgreSQL will be running locally, define the following
environment variables to override its location:

```shell
$ export PG_URL=jdbc:postgresql://localhost:5432/quests
$ export PG_USER=quests
$ export PG_PSW=quests
```
