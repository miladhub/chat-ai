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

postgres=# CREATE USER chat WITH PASSWORD 'chat';
CREATE ROLE
postgres=# CREATE DATABASE chat OWNER chat;
CREATE DATABASE
postgres=# \q
```

Install the extension on the schema by connecting as the superuser:

```shell
$ psql chat
psql (14.8 (Homebrew))
Type "help" for help.

chat=# CREATE EXTENSION vector;
CREATE EXTENSION
```

The project expects a table called `prompts`, here's how to create it:

```shell
$ psql chat chat
psql (14.8 (Homebrew))
Type "help" for help.

chat=> create table messages (
  id bigserial PRIMARY KEY,
  embedding vector(1536),
  role varchar(10),
  contents varchar(4000) UNIQUE
);
CREATE TABLE
chat=> create table contexts (
  id bigserial PRIMARY KEY,
  name varchar(100) UNIQUE,
  value varchar(400) not null
);
CREATE TABLE
chat=> create table model_functions (
  id bigserial PRIMARY KEY,
  name varchar(100) UNIQUE,
  body json not null
);
CREATE TABLE
```

To delete all past interactions:

```shell
$ psql chat chat
psql (14.8 (Homebrew))
Type "help" for help.

chat=> delete from prompts;
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
Hit Ctrl-D to exit.
To save or update context entries, type:
:context (save|delete) <entry-name> [<entry-value>]
Enjoy!
> :context save role you are my dungeons and dragons master, and I play the character FizzBuzz, a level 20 wizard
Model> context updated.
> who am I?
Model> As the Dungeon Master, it is up to you to create and control the world and its inhabitants.
You have the power to shape the story and guide the players through their adventures.
Your role is to describe the environments, non-player characters, and to facilitate the progression of the game.
So, in this case, you are the one orchestrating the game and playing all the other characters besides FizzBuzz.
> 
```

# Running the DB elsewhere

This guide assumes that PostgreSQL will be running locally, define the following
environment variables to override its location:

```shell
$ export PG_URL=jdbc:postgresql://localhost:5432/chat
$ export PG_USER=chat
$ export PG_PSW=chat
```
